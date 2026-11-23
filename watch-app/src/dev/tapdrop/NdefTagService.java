package dev.tapdrop;

import android.location.Location;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.Log;

import java.util.Arrays;

/**
 * Emulates an NFC Forum Type 4 tag that holds one NDEF message.
 *
 * Sharing: a reader selects the NDEF application, reads the capability container
 * (E103), then reads the NDEF file (E104). The message comes from the item whose
 * share screen is open. With TapDrop closed, the watch first makes the wearer
 * choose TapDrop on a system screen; the next tap then gets the quick-tap item.
 *
 * Learning: while the Learn screen is open the tag presents itself as empty and
 * writable. An NFC tag app writes a message with UPDATE BINARY, and the watch
 * saves it as a new item. Writes are refused at every other time, so a stranger's
 * phone cannot change what the watch holds.
 */
public class NdefTagService extends HostApduService {
    static final String TAG = "TapDrop";

    /** Count of completed reads. The share screen polls it to show "Sent". */
    static volatile int reads = 0;

    /** Count of messages learned, and the id of the newest. The Learn screen polls these. */
    static volatile int learned = 0;
    static volatile String lastLearnedId = null;

    /** Latest location fix, written by the share screen of a location item. */
    static volatile Location fix = null;

    /**
     * Which screen is open, set by that screen in onResume and cleared in onPause.
     *
     * These live in memory on purpose. They were once saved in preferences, and when the
     * app was killed with the Learn screen open the flag was never cleared: the watch went
     * on presenting an empty writable tag to every phone. A value that dies with the
     * process cannot be left behind like that.
     */
    static volatile String openItemId = null;       // the item whose share screen is showing
    static volatile boolean learnScreenOpen = false;

    private static final byte[] NDEF_AID = hex("D2760000850101");
    private static final byte[] OK = {(byte) 0x90, 0x00};
    private static final byte[] NOT_FOUND = {0x6A, (byte) 0x82};
    private static final byte[] WRONG_PARAMS = {0x6B, 0x00};
    private static final byte[] WRONG_LENGTH = {0x67, 0x00};
    private static final byte[] DENIED = {0x69, (byte) 0x82};
    private static final byte[] NOT_SUPPORTED = {0x6D, 0x00};

    /** Size of the NDEF file: a 2-byte length followed by the message. */
    private static final int MAX_FILE = 0x0800;

    private static final int NONE = 0;
    private static final int CC = 1;
    private static final int NDEF = 2;

    private final byte[] file = new byte[MAX_FILE];
    private int fileLen = 0;            // valid bytes in the file, 0 when there is nothing to serve
    private int selected = NONE;
    private boolean learning = false;
    private boolean idle = false;       // answering with no share screen open
    private boolean counted = false;    // this read has been counted
    private boolean saved = false;      // this write has been saved
    private int written = 0;            // highest message byte written so far

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        byte[] reply = handle(apdu);
        Log.d(TAG, "<- " + toHex(apdu) + "   -> " + toHex(reply));
        return reply;
    }

    private byte[] handle(byte[] apdu) {
        if (apdu == null || apdu.length < 4) return NOT_SUPPORTED;
        int ins = apdu[1] & 0xFF;
        int p1 = apdu[2] & 0xFF;
        int p2 = apdu[3] & 0xFF;

        if (ins == 0xA4) {                       // SELECT
            byte[] data = body(apdu);
            if (p1 == 0x04) return Arrays.equals(data, NDEF_AID) ? begin() : NOT_FOUND;
            if (fileLen > 0 && data.length == 2 && (data[0] & 0xFF) == 0xE1) {   // by file id
                if ((data[1] & 0xFF) == 0x03) { selected = CC; return OK; }
                if ((data[1] & 0xFF) == 0x04) { selected = NDEF; return OK; }
            }
            return NOT_FOUND;
        }

        if (ins == 0xB0) {                       // READ BINARY
            if (selected == NONE) return NOT_FOUND;
            byte[] source = selected == CC ? capabilityContainer() : file;
            int length = selected == CC ? source.length : fileLen;
            int offset = (p1 << 8) | p2;
            int le = apdu.length > 4 ? (apdu[4] & 0xFF) : 0;
            if (le == 0) le = 256;
            if (offset > length) return WRONG_PARAMS;
            int n = Math.min(le, length - offset);
            byte[] out = new byte[n + 2];
            System.arraycopy(source, offset, out, 0, n);
            out[n] = (byte) 0x90;
            out[n + 1] = 0x00;
            if (!learning && selected == NDEF && offset + n >= fileLen && !counted) {
                counted = true;
                reads++;                         // the share screen sees this and buzzes the wrist
                Log.d(TAG, "READ COMPLETE");
                if (idle) {                      // no share screen to do it, so confirm from here
                    Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[] {0, 35, 70, 55}, -1));
                    TapDropTileService.sent(this);
                }
            }
            return out;
        }

        if (ins == 0xD6) {                       // UPDATE BINARY
            if (!learning || selected != NDEF) return DENIED;
            byte[] data = body(apdu);
            int offset = (p1 << 8) | p2;
            if (data.length == 0 || offset + data.length > MAX_FILE) return WRONG_LENGTH;
            System.arraycopy(data, 0, file, offset, data.length);
            if (offset + data.length > 2) written = Math.max(written, offset + data.length);
            // A writer sets the length to 0, writes the message, then writes the real length last.
            int messageLen = ((file[0] & 0xFF) << 8) | (file[1] & 0xFF);
            fileLen = Math.min(MAX_FILE, 2 + messageLen);
            if (offset == 0 && messageLen > 0 && written >= 2 + messageLen && !saved) {
                saved = true;
                learn(Arrays.copyOfRange(file, 2, 2 + messageLen));
            }
            return OK;
        }

        return NOT_SUPPORTED;
    }

    /** A reader selected the tag. Decide what this tap is: a write to learn from, or a read to answer. */
    private byte[] begin() {
        selected = NONE;
        counted = false;
        saved = false;
        written = 0;
        idle = false;
        learning = learnScreenOpen;

        if (learning) {
            Arrays.fill(file, (byte) 0);
            fileLen = 2;                         // an empty tag: length 0 and no message
            Log.d(TAG, "learning: presenting an empty writable tag");
            return OK;
        }

        // Normally the item on screen. With TapDrop closed this point is reached only after the
        // wearer picked TapDrop on Samsung's "Select an app to use" screen and the phone tapped
        // again, so the quick-tap item goes out.
        String id = openItemId;
        idle = id == null;
        if (idle) id = Store.quick(this);
        Item item = Store.find(this, id);
        if (item != null && idle) Log.d(TAG, "no share screen open, answering with the quick-tap item id=" + id);
        byte[] message = item == null ? null : Kinds.record(this, item, fix);
        if (message == null || message.length + 2 > MAX_FILE) {
            Log.d(TAG, item == null ? "nothing to share yet, not answering"
                : message == null ? "nothing to send yet for " + item.type : "message too large: " + message.length);
            fileLen = 0;
            return NOT_FOUND;
        }
        file[0] = (byte) (message.length >> 8);
        file[1] = (byte) message.length;
        System.arraycopy(message, 0, file, 2, message.length);
        fileLen = 2 + message.length;
        Log.d(TAG, "serving type=" + item.type + " bytes=" + fileLen);
        return OK;
    }

    /** Saves a written message as a new item, if it is a well-formed NDEF message. */
    private void learn(byte[] message) {
        String[] what = Ndef.describe(message);
        if (what == null) {
            Log.w(TAG, "learned bytes are not a valid NDEF message, ignored: " + message.length);
            return;
        }
        Item item = new Item(Store.newId(), Kinds.CUSTOM, null);
        item.put("ndef", Base64.encodeToString(message, Base64.NO_WRAP));
        item.put("label", "");
        Store.upsert(this, item);
        lastLearnedId = item.id;
        learned++;                               // the Learn screen sees this
        Log.d(TAG, "LEARNED " + message.length + " bytes: " + what[0]);
    }

    /** Capability container: v2.0, 59-byte reads, 52-byte writes, one NDEF file E104. Writable only while learning. */
    private byte[] capabilityContainer() {
        return new byte[] {
            0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34,
            0x04, 0x06, (byte) 0xE1, 0x04,
            (byte) (MAX_FILE >> 8), (byte) MAX_FILE,
            0x00, (byte) (learning ? 0x00 : 0xFF)
        };
    }

    @Override
    public void onDeactivated(int reason) {
        Log.d(TAG, "deactivated, reason=" + reason);
        selected = NONE;
    }

    private static byte[] body(byte[] apdu) {
        if (apdu.length < 5) return new byte[0];
        int lc = apdu[4] & 0xFF;
        if (apdu.length < 5 + lc) return new byte[0];
        return Arrays.copyOfRange(apdu, 5, 5 + lc);
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String toHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
