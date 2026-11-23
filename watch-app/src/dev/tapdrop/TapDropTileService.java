package dev.tapdrop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.nfc.NfcAdapter;
import android.util.Log;

import androidx.concurrent.futures.ResolvableFuture;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.protolayout.TypeBuilders;
import androidx.wear.protolayout.expression.AnimationParameterBuilders;
import androidx.wear.protolayout.expression.DynamicBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tile that shows the item TapDrop shares while closed, with the same icon the
 * app's lists use. Tapping it opens the Quick tap page, where the item is chosen.
 *
 * It animates like the share screen (CardView): while the watch would answer a
 * phone with the item, rings travel out to the edge, and when a phone has read
 * it the icon turns into a tick and one bright ring bursts out. A tile cannot
 * draw frames itself, and tile hosts do not play animated drawables, so each ring
 * is a circular border whose scale and colour the host animates.
 */
public final class TapDropTileService extends TileService {
    private static final String ICON = "icon";
    private static final String TICK = "tick";
    private static final int ICON_DP = 40;

    // CardView's numbers, on its 438-unit design: the rim sits 9 units inside the edge.
    private static final float RIM = 1f - 18f / 438f;   // rim diameter, as a share of the screen
    private static final long WAVE_MS = 2400;
    private static final long BURST_MS = 620;
    /** CardView's ease for the rings: 1 - (1 - t)^2, as a cubic Bezier. */
    private static final AnimationParameterBuilders.Easing EASE_OUT =
        AnimationParameterBuilders.Easing.cubicBezier(0.25f, 0.46f, 0.45f, 0.94f);
    /** As long as the share screen shows "Sent". */
    private static final long SENT_MS = 2500;
    /** A read older than this when the tile is next drawn is not shown as sent. */
    private static final long SENT_LATE_MS = 10_000;

    /** When a phone last finished reading the quick-tap item, by the wall clock. In memory on purpose. */
    private static volatile long sentAt = 0;

    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        Item quick = Store.find(this, Store.quick(this));
        String glyph = quick == null ? Icons.NONE : quick.type;
        int color = quick == null ? Ui.NEUTRAL : Kinds.color(quick.type);
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
        boolean nfcOff = nfc == null || !nfc.isEnabled();
        boolean ready = quick != null && !nfcOff;
        long now = System.currentTimeMillis();
        boolean sent = ready && now - sentAt < SENT_LATE_MS;

        // The tile host launches the activity itself, so this is not a background start.
        ActionBuilders.LaunchAction openSettings = new ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                .setPackageName(getPackageName())
                .setClassName(OneTapActivity.class.getName())
                .build())
            .build();
        ModifiersBuilders.Modifiers modifiers = new ModifiersBuilders.Modifiers.Builder()
            .setClickable(new ModifiersBuilders.Clickable.Builder()
                .setId("open_quick_tap")
                .setOnClick(openSettings)
                .build())
            .build();

        String title = quick == null ? "Nothing" : Kinds.title(quick);
        String status = quick == null ? "Shares nothing" : nfcOff ? "NFC is off" : "Sharing now";
        int statusColor = quick != null && nfcOff ? Ui.DANGER : Ui.ACCENT;
        float rim = request.getDeviceConfiguration().getScreenWidthDp() * RIM;
        LayoutElementBuilders.LayoutElement shown =
            layout(modifiers, ready ? waves(rim, color) : null, ICON, title, status, statusColor, false);

        TimelineBuilders.Timeline timeline;
        if (sent) {
            // "Sent" from now, then the usual tile, both handed over at once so the host
            // switches back by itself. Timed from now, since the host may ask a moment late.
            LayoutElementBuilders.LayoutElement flash =
                layout(modifiers, burst(rim, color), TICK, title, "Sent", Ui.TEXT, true);
            sentAt = 0;
            timeline = new TimelineBuilders.Timeline.Builder()
                .addTimelineEntry(entry(flash, now, now + SENT_MS))
                .addTimelineEntry(entry(shown, now + SENT_MS, 0))
                .build();
        } else {
            timeline = TimelineBuilders.Timeline.fromLayoutElement(shown);
        }

        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
            // The version names the icon, so the host asks for it again only when it changes.
            .setResourcesVersion(glyph + "/" + Integer.toHexString(color))
            .setTileTimeline(timeline)
            .build();
        return completed(tile);
    }

    /** One state of the tile: rings behind (or none), then the icon and three lines. */
    private static LayoutElementBuilders.LayoutElement layout(ModifiersBuilders.Modifiers modifiers,
            List<LayoutElementBuilders.LayoutElement> rings, String icon, String title, String status,
            int statusColor, boolean boldStatus) {
        LayoutElementBuilders.Column column = new LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                .setPadding(new ModifiersBuilders.Padding.Builder()
                    .setStart(DimensionBuilders.dp(20))
                    .setEnd(DimensionBuilders.dp(20))
                    .build())
                .build())
            .addContent(text("Quick tap", 15, Ui.TEXT_SECONDARY, false))
            .addContent(spacer(6))
            .addContent(new LayoutElementBuilders.Image.Builder()
                .setResourceId(icon)
                .setWidth(DimensionBuilders.dp(ICON_DP))
                .setHeight(DimensionBuilders.dp(ICON_DP))
                .build())
            .addContent(spacer(6))
            .addContent(text(title, 20, Ui.TEXT, true))
            .addContent(spacer(4))
            .addContent(text(status, 14, statusColor, boldStatus))
            .addContent(spacer(10))
            .addContent(text("Tap to change", 13, Ui.TEXT_TERTIARY, false))
            .build();

        LayoutElementBuilders.Box.Builder box = new LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(modifiers); // the whole tile opens the Quick tap page
        if (rings != null) for (LayoutElementBuilders.LayoutElement ring : rings) box.addContent(ring);
        return box.addContent(column).build();
    }

    /** A steady rim, and two waves travelling out to it half a cycle apart, without end. */
    private static List<LayoutElementBuilders.LayoutElement> waves(float rim, int color) {
        List<LayoutElementBuilders.LayoutElement> rings = new ArrayList<>();
        rings.add(ring(rim, 2f, ColorBuilders.argb(alpha(color, 95)), new TypeBuilders.FloatProp.Builder(1f).build()));
        for (int i = 0; i < 2; i++) {
            // Radius 87% to 100% of the rim, alpha 150 of 255 to 0, both eased out.
            AnimationParameterBuilders.AnimationSpec spec = endless(WAVE_MS, i * WAVE_MS / 2);
            rings.add(ring(rim, 2f,
                new ColorBuilders.ColorProp.Builder(alpha(color, 0))
                    .setDynamicValue(DynamicBuilders.DynamicColor.animate(alpha(color, 150), alpha(color, 0), spec))
                    .build(),
                new TypeBuilders.FloatProp.Builder(1f)
                    .setDynamicValue(DynamicBuilders.DynamicFloat.animate(0.87f, 1f, spec))
                    .build()));
        }
        return rings;
    }

    /** The rim at full strength, and one bright ring leaving the middle for the edge, once. */
    private static List<LayoutElementBuilders.LayoutElement> burst(float rim, int color) {
        List<LayoutElementBuilders.LayoutElement> rings = new ArrayList<>();
        rings.add(ring(rim, 3.5f, ColorBuilders.argb(color), new TypeBuilders.FloatProp.Builder(1f).build()));
        AnimationParameterBuilders.AnimationSpec spec = new AnimationParameterBuilders.AnimationSpec.Builder()
            .setAnimationParameters(parameters(BURST_MS, 0))
            .build();
        // From a 30-unit radius around the middle to the rim, alpha 230 of 255 to 0.
        rings.add(ring(rim, 6f,
            new ColorBuilders.ColorProp.Builder(alpha(color, 0))
                .setDynamicValue(DynamicBuilders.DynamicColor.animate(alpha(color, 230), alpha(color, 0), spec))
                .build(),
            new TypeBuilders.FloatProp.Builder(1f)
                .setDynamicValue(DynamicBuilders.DynamicFloat.animate(30f / 210f, 1f, spec))
                .build()));
        return rings;
    }

    /** A circle outline the size of the rim, centred, scaled about its middle. */
    private static LayoutElementBuilders.LayoutElement ring(float diameter, float stroke,
            ColorBuilders.ColorProp color, TypeBuilders.FloatProp scale) {
        return new LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(diameter))
            .setHeight(DimensionBuilders.dp(diameter))
            .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                .setBorder(new ModifiersBuilders.Border.Builder()
                    .setWidth(DimensionBuilders.dp(stroke))
                    .setColor(color)
                    .build())
                .setBackground(new ModifiersBuilders.Background.Builder()
                    .setCorner(new ModifiersBuilders.Corner.Builder()
                        .setRadius(DimensionBuilders.dp(diameter / 2f))
                        .build())
                    .build())
                .setTransformation(new ModifiersBuilders.Transformation.Builder()
                    .setScaleX(scale)
                    .setScaleY(scale)
                    .build())
                .build())
            .build();
    }

    /** Plays for the given time after the delay, then repeats straight away, without end. */
    private static AnimationParameterBuilders.AnimationSpec endless(long millis, long delay) {
        return new AnimationParameterBuilders.AnimationSpec.Builder()
            .setAnimationParameters(parameters(millis, delay))
            .setRepeatable(new AnimationParameterBuilders.Repeatable.Builder()
                .setRepeatMode(AnimationParameterBuilders.REPEAT_MODE_RESTART)
                .setForwardRepeatOverride(parameters(millis, 0))
                .build())
            .build();
    }

    private static AnimationParameterBuilders.AnimationParameters parameters(long millis, long delay) {
        return new AnimationParameterBuilders.AnimationParameters.Builder()
            .setDurationMillis(millis)
            .setDelayMillis(delay)
            .setEasing(EASE_OUT)
            .build();
    }

    private static int alpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private static TimelineBuilders.TimelineEntry entry(
            LayoutElementBuilders.LayoutElement layout, long start, long end) {
        TimelineBuilders.TimeInterval.Builder validity = new TimelineBuilders.TimeInterval.Builder()
            .setStartMillis(start);
        if (end > 0) validity.setEndMillis(end);
        return new TimelineBuilders.TimelineEntry.Builder()
            .setValidity(validity.build())
            .setLayout(LayoutElementBuilders.Layout.fromLayoutElement(layout))
            .build();
    }

    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            RequestBuilders.ResourcesRequest request) {
        String version = request.getVersion();
        int slash = version.lastIndexOf('/');
        ResourceBuilders.Resources.Builder resources = new ResourceBuilders.Resources.Builder()
            .setVersion(version);
        if (slash > 0) {
            try {
                int color = (int) Long.parseLong(version.substring(slash + 1), 16);
                resources.addIdToImageMapping(ICON, icon(version.substring(0, slash), color));
                resources.addIdToImageMapping(TICK, icon(Icons.CHECK, color));
            } catch (NumberFormatException e) {
                Log.w(NdefTagService.TAG, "unexpected tile resource version " + version);
            }
        }
        return completed(resources.build());
    }

    /** The item's icon as the app's lists draw it (see IconView): a coloured disc, white glyph. */
    private ResourceBuilders.ImageResource icon(String glyph, int color) {
        int px = Ui.dp(this, ICON_DP);
        Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(color);
        canvas.drawCircle(px / 2f, px / 2f, px / 2f, disc);
        Icons.draw(canvas, glyph, px / 2f, px / 2f, px * 0.6f, Ui.TEXT);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        bitmap.recycle();
        return new ResourceBuilders.ImageResource.Builder()
            .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                .setData(png.toByteArray())
                .setWidthPx(px)
                .setHeightPx(px)
                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED) // PNG-encoded
                .build())
            .build();
    }

    /** Called by the tag service when a phone has read the quick-tap item: shows "Sent". */
    static void sent(Context context) {
        sentAt = System.currentTimeMillis();
        refresh(context);
    }

    /** Asks the system to redraw the tile after the quick-tap item or the item list changes. */
    static void refresh(Context context) {
        try {
            TileService.getUpdater(context).requestUpdate(TapDropTileService.class);
        } catch (RuntimeException e) {
            Log.d(NdefTagService.TAG, "tile refresh unavailable", e);
        }
    }

    private static LayoutElementBuilders.Text text(String value, int sp, int color, boolean bold) {
        return new LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sp))
                .setColor(ColorBuilders.argb(color))
                .setWeight(bold ? LayoutElementBuilders.FONT_WEIGHT_BOLD
                    : LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build())
            .build();
    }

    private static LayoutElementBuilders.Spacer spacer(int dp) {
        return new LayoutElementBuilders.Spacer.Builder()
            .setHeight(DimensionBuilders.dp(dp))
            .build();
    }

    private static <T> ListenableFuture<T> completed(T value) {
        ResolvableFuture<T> future = ResolvableFuture.create();
        future.set(value);
        return future;
    }
}
