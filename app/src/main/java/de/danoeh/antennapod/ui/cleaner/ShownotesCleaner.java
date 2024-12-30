package de.danoeh.antennapod.ui.cleaner;

import static java.lang.Integer.max;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.Nullable;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.ui.common.Converter;

/**
 * Cleans up and prepares shownotes:
 *  - Guesses time stamps to make them clickable
 *  - Removes some formatting
 */
public class ShownotesCleaner {
    private static final String TAG = "Timeline";

    private static final Pattern TIMECODE_LINK_REGEX = Pattern.compile("antennapod://timecode/(\\d+)");
    private static final String TIMECODE_LINK = "<a class=\"timecode\" href=\"antennapod://timecode/%d\">%s</a>";
    private static final Pattern TIMECODE_REGEX = Pattern.compile("\\b((\\d+):)?(\\d+):(\\d{2})\\b");
    private static final Pattern LINE_BREAK_REGEX = Pattern.compile("<br */?>");
    private static final Pattern HTTP_LINK_REGEX = Pattern.compile("http[s]?://(?:www\\.)?[-a-zA-Z0-9@%._+~#=]"
            + "{1,256}\\.[a-z]{2,12}\\b[-a-zA-Z0-9@:%_+.~#?&/=]*");
    private static final String CSS_COLOR = "(?<=(\\s|;|^))color\\s*:([^;])*;";
    private static final String CSS_COMMENT = "/\\*.*?\\*/";

    private final String rawShownotes;
    private final String noShownotesLabel;
    private final int playableDuration;
    private final String webviewStyle;

    public ShownotesCleaner(Context context, @Nullable String rawShownotes, int playableDuration) {
        this.rawShownotes = rawShownotes;

        noShownotesLabel = context.getString(R.string.no_shownotes_label);
        this.playableDuration = playableDuration;
        final String colorPrimary = colorToHtml(context, android.R.attr.textColorPrimary);
        final String colorAccent = colorToHtml(context, R.attr.colorAccent);
        final int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                context.getResources().getDisplayMetrics());
        String styleString = "";
        try {
            InputStream templateStream = context.getAssets().open("shownotes-style.css");
            styleString = IOUtils.toString(templateStream, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
        webviewStyle = String.format(Locale.US, styleString, colorPrimary, colorAccent,
                margin, margin, margin, margin);
    }

    private String colorToHtml(Context context, int colorAttr) {
        TypedArray res = context.getTheme().obtainStyledAttributes(new int[]{colorAttr});
        @ColorInt int col = res.getColor(0, 0);
        final String color = "rgba(" + Color.red(col) + "," + Color.green(col) + ","
                + Color.blue(col) + "," + (Color.alpha(col) / 255.0) + ")";
        res.recycle();
        return color;
    }

    /**
     * Applies an app-specific CSS stylesheet and adds timecode links (optional).
     * <p/>
     * This method does NOT change the original shownotes string of the shownotesProvider object and it should
     * also not be changed by the caller.
     *
     * @return The processed HTML string.
     */
    @NonNull
    public String processShownotes() {
        String shownotes = convertPlainTextLinksToHtml(rawShownotes);

        if (TextUtils.isEmpty(shownotes)) {
            Log.d(TAG, "shownotesProvider contained no shownotes. Returning 'no shownotes' message");
            shownotes = "<html><head></head><body><p id='apNoShownotes'>" + noShownotesLabel + "</p></body></html>";
        }

        // replace ASCII line breaks with HTML ones if shownotes don't contain HTML line breaks already
        if (!LINE_BREAK_REGEX.matcher(shownotes).find() && !shownotes.contains("<p>")) {
            shownotes = shownotes.replace("\n", "<br />");
        }

        Document document = Jsoup.parse(shownotes);
        cleanCss(document);
        document.head().appendElement("style").attr("type", "text/css").text(webviewStyle);
        addTimecodes(document);
        document.body().attr("dir", "auto");
        return document.toString();
    }

    /**
     * Returns true if the given link is a timecode link.
     */
    public static boolean isTimecodeLink(String link) {
        return link != null && link.matches(TIMECODE_LINK_REGEX.pattern());
    }

    /**
     * Returns the time in milliseconds that is attached to this link or -1
     * if the link is no valid timecode link.
     */
    public static int getTimecodeLinkTime(String link) {
        if (isTimecodeLink(link)) {
            Matcher m = TIMECODE_LINK_REGEX.matcher(link);

            try {
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
        return -1;
    }

    private void addTimecodes(Document document) {
        Elements elementsWithTimeCodes = document.body().getElementsMatchingOwnText(TIMECODE_REGEX);
        Log.d(TAG, "Recognized " + elementsWithTimeCodes.size() + " timecodes");

        if (elementsWithTimeCodes.size() == 0) {
            // No elements with timecodes
            return;
        }
        boolean useHourFormat = true;

        if (playableDuration != Integer.MAX_VALUE) {

            // We need to decide if we are going to treat short timecodes as HH:MM or MM:SS. To do
            // so we will parse all the short timecodes and see if they fit in the duration. If one
            // does not we will use MM:SS, otherwise all will be parsed as HH:MM.
            for (Element element : elementsWithTimeCodes) {
                Matcher matcherForElement = TIMECODE_REGEX.matcher(element.html());
                while (matcherForElement.find()) {

                    // We only want short timecodes right now.
                    if (matcherForElement.group(1) == null) {
                        int time = Converter.durationStringShortToMs(matcherForElement.group(0), true);

                        // If the parsed timecode is greater then the duration then we know we need to
                        // use the minute format so we are done.
                        if (time > playableDuration) {
                            useHourFormat = false;
                            break;
                        }
                    }
                }

                if (!useHourFormat) {
                    break;
                }
            }
        }

        for (Element element : elementsWithTimeCodes) {

            Matcher matcherForElement = TIMECODE_REGEX.matcher(element.html());
            StringBuffer buffer = new StringBuffer();

            while (matcherForElement.find()) {
                String group = matcherForElement.group(0);

                int time = matcherForElement.group(1) != null
                                        ? Converter.durationStringLongToMs(group)
                                        : Converter.durationStringShortToMs(group, useHourFormat);

                String replacementText = group;
                if (time < playableDuration) {
                    replacementText = String.format(Locale.US, TIMECODE_LINK, time, group);
                }

                matcherForElement.appendReplacement(buffer, replacementText);
            }

            matcherForElement.appendTail(buffer);
            element.html(buffer.toString());
        }
    }

    private void cleanCss(Document document) {
        for (Element element : document.getAllElements()) {
            if (element.hasAttr("style")) {
                element.attr("style", element.attr("style").replaceAll(CSS_COLOR, ""));
            } else if (element.tagName().equals("style")) {
                element.html(cleanStyleTag(element.html()));
            }
        }
    }

    public static String cleanStyleTag(String oldCss) {
        return oldCss.replaceAll(CSS_COMMENT, "").replaceAll(CSS_COLOR, "");
    }

    /**
     * Provided text can be a mixture of plain-text links and html links.
     * Only plain-text links will be changed (converted to html)
     */
    public static String convertPlainTextLinksToHtml(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        var alreadyHtmlLinks = new HashSet<String>();
        int lastIndex = 0;
        StringBuilder output = new StringBuilder();
        var m = HTTP_LINK_REGEX.matcher(text);
        var tagIndicators = Set.of('\'', '\"', '=');
        while (m.find()) {
            var candidate = m.group();
            var prevPos = max(0, m.start() - 1);
            var prevChar = text.charAt(prevPos);
            var isInsideTag = tagIndicators.contains(prevChar);
            if (isInsideTag) {
                alreadyHtmlLinks.add(candidate);
            }
            var transformed = alreadyHtmlLinks.contains(candidate) ? candidate : makeLinkHtml(candidate);
            output.append(text, lastIndex, m.start()).append(transformed);
            lastIndex = m.end();
        }
        if (lastIndex < text.length()) {
            output.append(text, lastIndex, text.length());
        }

        return output.toString();
    }

    /**
     * Adds &lt;a href=...&gt;...&lt;/a&gt; around provided string
     */
    public static String makeLinkHtml(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        return "<a href=\"" + plain + "\">" + plain + "</a>";
    }
}
