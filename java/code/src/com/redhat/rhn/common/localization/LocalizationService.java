/*
 * Copyright (c) 2009--2016 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.common.localization;

import com.redhat.rhn.common.conf.Config;
import com.redhat.rhn.common.conf.ConfigDefaults;
import com.redhat.rhn.common.db.datasource.DataResult;
import com.redhat.rhn.common.db.datasource.ModeFactory;
import com.redhat.rhn.common.db.datasource.SelectMode;
import com.redhat.rhn.common.util.StringUtil;
import com.redhat.rhn.frontend.context.Context;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.text.Collator;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Localization service class to simplify the job for producing localized
 * (translated) strings within the product.
 *
 */
public class LocalizationService {

    /**
     * DateFormat used by RHN database queries. Useful for converting RHN dates
     * into java.util.Dates so they can be formatted based on Locale.
     */
    public static final String RHN_DB_DATEFORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String RHN_CUSTOM_DATEFORMAT = "yyyy-MM-dd HH:mm:ss z";
    private static final String DOC_FOLDER = "/usr/share/susemanager/www/htdocs/docs";

    private static Logger log = LogManager.getLogger(LocalizationService.class);
    private static Logger msgLogger = LogManager.getLogger("com.redhat.rhn.common.localization.messages");

    public static final Locale DEFAULT_LOCALE = getDefaultLocale();
    // private instance of the service.
    private static LocalizationService instance = new LocalizationService();
    // This Map stores the association of the java class names
    // that map to the message keys found in the StringResources.xml
    // files. This allows us to have sets of XML ResourceBundles that
    // are specified in the rhn.jconf
    private Map<String, String> keyToBundleMap;

    // List of supported locales
    private final Map<String, LocaleInfo> supportedLocales =
            new HashMap<>();

    /**
     * hidden constructor
     */
    private LocalizationService() {
        initService();
    }

    /**
     * Initialize the set of strings and keys used by the service
     */
    protected void initService() {
        // If we are reloading, lets log it.
        if (keyToBundleMap != null) {
            // We want to note in the log that we are doing this
            log.warn("Reloading XML StringResource files.");
            XmlMessages.getInstance().resetBundleCache();
        }
        keyToBundleMap = new HashMap<>();

        // Get the list of configured classnames from the config file.
        String[] packages = Config.get().getStringArray(
                ConfigDefaults.WEB_L10N_RESOURCEBUNDLES);
        for (String packageIn : packages) {
            addKeysToMap(packageIn);
        }
        if (!supportedLocales.isEmpty()) {
            supportedLocales.clear();
        }
        loadSupportedLocales();
    }

    /** Add the keys from the specified class to the Service's Map. */
    private void addKeysToMap(String className) {
        try {
            Class<?> z = Class.forName(className);
            // All the keys must exist in the en_US XML files first. The other
            // languages may have subsets but no unique keys. If this is a
            // problem
            // refactoring will need to take place.
            Enumeration<String> e = XmlMessages.getInstance().getKeys(z, Locale.US);
            while (e.hasMoreElements()) {
                String key = e.nextElement();
                keyToBundleMap.put(key, className);
            }
        }
        catch (ClassNotFoundException ce) {
            String message = "Class not found when trying to initalize the LocalizationService: " + ce;
            log.error(message, ce);
            throw new LocalizationException(message, ce);
        }
    }

    private void loadSupportedLocales() {
        String rawLocales = Config.get().getString("java.supported_locales");
        if (rawLocales == null) {
            return;
        }
        List<String> compoundLocales = new LinkedList<>();
        for (Enumeration<Object> locales = new StringTokenizer(rawLocales, ","); locales
                .hasMoreElements();) {
            String locale = (String) locales.nextElement();
            if (locale.indexOf('_') > -1) {
                compoundLocales.add(locale);
            }
            LocaleInfo li = new LocaleInfo(locale);
            this.supportedLocales.put(locale, li);
        }
        for (String cl : compoundLocales) {
            String[] parts = cl.split("_");
            LocaleInfo li = new LocaleInfo(parts[0], cl);
            this.supportedLocales.putIfAbsent(parts[0], li);
        }
    }

    /**
     * Get the running instance of the LocalizationService
     *
     * @return The LocalizationService singleton
     */
    public static LocalizationService getInstance() {
        return instance;
    }

    /**
     * Reload the resource files from the disk. Only works in development mode.
     * @return boolean if we reloaded the files or not.
     */
    public boolean reloadResourceFiles() {
        if (Config.get().getBoolean("java.development_environment")) {
            initService();
            return true;
        }
        log.error("Tried to reload XML StringResource files but " +
                "we aren't in java.development_environment mode");
        return false;
    }

    /**
     * Get a localized version of a String and let the service attempt to figure
     * out the callee's locale.
     * @param messageId The key of the message we are fetching
     * @return Translated String
     */
    public String getMessage(String messageId) {
        Context ctx = Context.getCurrentContext();
        return getMessage(messageId, ctx.getLocale());
    }

    /**
     * Get a localized version of a string with the specified locale.
     * @param messageId The key of the message we are fetching
     * @param args arguments for message.
     * @return Translated String
     */
    public String getMessage(String messageId, Object... args) {
        Context ctx = Context.getCurrentContext();
        return getMessage(messageId, ctx.getLocale(), args);
    }

    /**
     * Gets a  Plain Text + localized version of a string with the default locale.
     * @param messageId The key of the message we are fetching
     * @param args arguments for message.
     * @return Translated String
     */
    public String getPlainText(String messageId, Object... args) {
        String msg = getMessage(messageId, args);
        String unescaped = StringEscapeUtils.unescapeHtml4(msg);
        return StringUtil.toPlainText(unescaped);
    }

    /**
     * Gets a  Plain Text + localized version of a string with the default locale.
     * @param messageId The key of the message we are fetching
     * @return Translated String
     */
    public String getPlainText(String messageId) {
        return getPlainText(messageId, (Object[])null);
    }
    /**
     * Take in a String array of keys and transform it into a String array of
     * localized Strings.
     * @param keys String[] array of key values
     * @return String[] array of localized strings.
     */
    public String[] getMessages(String[] keys) {
        String[] retval = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            retval[i] = getMessage(keys[i]);
        }
        return retval;
    }

    /**
     * Get a localized version of a string with the specified locale.
     * @param messageId The key of the message we are fetching
     * @param locale The locale to use when fetching the string
     * @param args arguments for message.
     * @return Translated String
     */
    public String getMessage(String messageId, Locale locale, Object... args) {
        String logSafeMessageId = StringUtil.sanitizeLogInput(messageId);
        if (log.isDebugEnabled()) {
            log.debug("getMessage() called with messageId: {} and locale: {}", logSafeMessageId, locale);
        }
        // Short-circuit the rest of the method if the messageId is null
        // See bz 199892
        if (messageId == null) {
            return getMissingMessageString(messageId);
        }
        String userLocale = locale == null ? "null" : locale.toString();
        if (msgLogger.isDebugEnabled()) {
            msgLogger.debug("Resolving message \"{}\" for locale {}", logSafeMessageId, userLocale);
        }
        String mess;
        Class<?> z = null;
        try {
            // If the keyMap doesn't contain the requested key
            // then there is no hope and we return.
            if (!keyToBundleMap.containsKey(messageId)) {
                return getMissingMessageString(messageId);
            }
            z = Class.forName(keyToBundleMap.get(messageId));
            // If we already determined that there aren't an bundles
            // for this Locale then we shouldn't repeatedly fail
            // attempts to parse the bundle. Instead just force a
            // call to the default Locale.
            mess = XmlMessages.getInstance().format(z, locale,
                    messageId, args);
        }
        catch (MissingResourceException e) {
            // Try again with DEFAULT_LOCALE
            if (msgLogger.isDebugEnabled()) {
                msgLogger.debug("Resolving message \"{}\" for locale {} failed -  trying again with default locale {}",
                        logSafeMessageId, userLocale, DEFAULT_LOCALE);
            }
            try {
                mess = XmlMessages.getInstance().format(z, DEFAULT_LOCALE,
                        messageId, args);
            }
            catch (MissingResourceException mre) {
                if (msgLogger.isDebugEnabled()) {
                    msgLogger.debug("Resolving message \"{}\" for default locale {} failed", messageId,
                            DEFAULT_LOCALE);
                }
                return getMissingMessageString(messageId);
            }
        }
        catch (ClassNotFoundException ce) {
            String message = "Class not found when trying to fetch a message: " + ce;
            log.error(message, ce);
            throw new LocalizationException(message, ce);
        }
        return getDebugVersionOfString(mess);
    }

    private String getDebugVersionOfString(String mess) {
        // If we have put the Service into debug mode we
        // will wrap all the messages in a marker.
        boolean debugMode = Config.get().getBoolean("java.l10n_debug");
        if (debugMode) {
            StringBuilder debug = new StringBuilder();
            String marker = Config.get().getString("java.l10n_debug_marker",
                    "$$$");
            debug.append(marker);
            debug.append(mess);
            debug.append(marker);
            mess = debug.toString();
        }
        return mess;
    }

    // returns the first class/method that does not belong to this
    // package (who calls this actually) - for debugging purposes
    private StackTraceElement getCallingMethod() {
        try {
            throw new RuntimeException("Stacktrace Dummy Exception");
        }
        catch (RuntimeException e) {
            try {
                final String prefix = this.getClass().getPackage().getName();
                for (StackTraceElement element : e.getStackTrace()) {
                    if (!element.getClassName().startsWith(prefix)) {
                        return element;
                    }
                }
            }
            catch (Exception err) {
                // dont break - return nothing rather than stop
                return null;
            }
        }
        return null;
    }

    private String getMissingMessageString(String messageId) {
        String caller = "";
        StackTraceElement callerElement = getCallingMethod();
        if (callerElement != null) {
            caller = " called by " + callerElement;
        }
        if (messageId == null) {
            messageId = "null";
        }
        String message = "*** ERROR: Message with id: [" + StringUtil.sanitizeLogInput(messageId) +
                "] not found.***" + caller;
        log.error(message);
        boolean exceptionMode = Config.get().getBoolean(
                "java.l10n_missingmessage_exceptions");
        if (exceptionMode) {
            throw new IllegalArgumentException(message);
        }
        return StringEscapeUtils.escapeHtml4("**" + messageId + "**");
    }

    /**
     * Get localized text for log messages as well as error emails. Determines
     * Locale of running JVM vs using the current Thread or any other User
     * related Locale information. TODO mmccune Get Locale out of Config or from
     * the JVM
     * @param messageId The key of the message we are fetching
     * @return String debug message.
     */
    public String getDebugMessage(String messageId) {
        return getMessage(messageId, Locale.US);
    }

    /**
     * Format the date and let the service determine the locale
     * @param date Date to be formatted.
     * @return String representation of given date.
     */
    public String formatDate(Date date) {
        Context ctx = Context.getCurrentContext();
        return formatDate(date, ctx.getLocale());
    }

    /**
     * Format the instant and let the service determine the locale
     * @param instant the instant
     * @return String representation of given date.
     */
    public String formatDate(Instant instant) {
        return formatDate(Date.from(instant));
    }

    /**
     * Format the date as a short date depending on locale (YYYY-MM-DD in the
     * US)
     * @param date Date to be formatted
     * @return String representation of given date.
     */
    public String formatShortDate(Date date) {
        Context ctx = Context.getCurrentContext();
        return formatShortDate(date, ctx.getLocale());
    }

    /**
     * Format the date as a short date depending on locale (YYYY-MM-DD in the
     * US)
     *
     * @param date Date to be formatted
     * @param locale Locale to use for formatting
     * @return String representation of given date.
     */
    public String formatShortDate(Date date, Locale locale) {

        StringBuilder dbuff = new StringBuilder();
        DateFormat dateI = DateFormat.getDateInstance(DateFormat.SHORT, locale);

        dbuff.append(dateI.format(date));

        return getDebugVersionOfString(dbuff.toString());
    }

    /**
     * Use today's date and get it back localized and as a String
     * @return String representation of today's date.
     */
    public String getBasicDate() {
        return formatDate(new Date());
    }

    /**
     * Format the date based on the locale and convert it to a String to
     * display. Uses DateFormat.SHORT. Example: 2004-12-10 13:20:00 PST
     *
     * Also includes the timezone of the current User if there is one
     *
     * @param date Date to format.
     * @param locale Locale to use for formatting.
     * @return String representation of given date in given locale.
     */
    public String formatDate(Date date, Locale locale) {
        // Example: 2004-12-10 13:20:00 PST
        StringBuilder dbuff = new StringBuilder();
        DateFormat dateI = DateFormat.getDateInstance(DateFormat.SHORT, locale);
        dateI.setTimeZone(determineTimeZone());
        DateFormat timeI = DateFormat.getTimeInstance(DateFormat.LONG, locale);
        timeI.setTimeZone(determineTimeZone());

        dbuff.append(dateI.format(date));
        dbuff.append(" ");
        dbuff.append(timeI.format(date));

        return getDebugVersionOfString(dbuff.toString());
    }

    /**
     * Returns fixed custom format string displayed for the determined timezone
     * Example: 2010-04-01 15:04:24 CEST
     *
     * @param date Date to format.
     * @return String representation of given date for set timezone
     */
    public String formatCustomDate(Date date) {
        TimeZone tz = determineTimeZone();
        SimpleDateFormat sdf = new SimpleDateFormat(RHN_CUSTOM_DATEFORMAT);
        sdf.setTimeZone(tz);
        return sdf.format(date);
    }

    /**
     * Format the Number based on the locale and convert it to a String to
     * display.
     * @param numberIn Number to format.
     * @return String representation of given number in given locale.
     */
    public String formatNumber(Number numberIn) {
        Context ctx = Context.getCurrentContext();
        return formatNumber(numberIn, ctx.getLocale());
    }

    /**
     * Format the Number based on the locale and convert it to a String to
     * display. Use a specified number of fraction digits.
     * @param numberIn Number to format.
     * @param fractionalDigits The number of fractional digits to use. This is
     * both the minimum and maximum that will be displayed.
     * @return String representation of given number in given locale.
     */
    public String formatNumber(Number numberIn, int fractionalDigits) {
        Context ctx = Context.getCurrentContext();
        return formatNumber(numberIn, ctx.getLocale(), fractionalDigits);
    }

    /**
     * Format the Number based on the locale and convert it to a String to
     * display.
     * @param numberIn Number to format.
     * @param localeIn Locale to use for formatting.
     * @return String representation of given number in given locale.
     */
    public String formatNumber(Number numberIn, Locale localeIn) {
        return getDebugVersionOfString(NumberFormat.getInstance(localeIn)
                .format(numberIn));
    }

    /**
     * Format the Number based on the locale and convert it to a String to
     * display. Use a specified number of fractional digits.
     * @param numberIn Number to format.
     * @param localeIn Locale to use for formatting.
     * @param fractionalDigits The maximum number of fractional digits to use.
     * @return String representation of given number in given locale.
     */
    public String formatNumber(Number numberIn, Locale localeIn,
            int fractionalDigits) {
        NumberFormat nf = NumberFormat.getInstance(localeIn);
        nf.setMaximumFractionDigits(fractionalDigits);
        return getDebugVersionOfString(nf.format(numberIn));
    }

    /**
     * Get alphabet list for callee's Thread's Locale
     * @return the list of alphanumeric characters from the alphabet
     */
    public List<String> getAlphabet() {
        return StringUtil.stringToList(getMessage("alphabet"));
    }

    /**
     * Get digit list for callee's Thread's Locale
     * @return the list of digits
     */
    public List<String> getDigits() {
        return StringUtil.stringToList(getMessage("digits"));
    }

    /**
     * Get a list of available prefixes and ensure that it is sorted by
     * returning a SortedSet object.
     * @return SortedSet sorted set of available prefixes.
     */
    public SortedSet<String> availablePrefixes() {
        SelectMode prefixMode = ModeFactory.getMode("util_queries",
                "available_prefixes");
        // no params for this query
        DataResult<Map<String, Object>> dr = prefixMode.execute(new HashMap<>());

        SortedSet<String> ret = new TreeSet<>();
        for (Map<String, Object> row : dr) {
            ret.add((String) row.get("prefix"));
        }
        return ret;
    }

    /**
     * Get a SortedMap containing NAME/CODE value pairs. The reason we key the
     * Map based on the NAME is that we desire to maintain a localized sort
     * order based on the display value and not the code.
     *
     * <pre>
     *     {name=Spain,     code=ES}
     *     {name=Sri Lanka, code=LK}
     *     {name=Sudan,     code=SD}
     *     {name=Suriname,  code=SR, }
     *     etc ...
     * </pre>
     *
     * @return SortedMap sorted map of available countries.
     */
    public SortedMap<String, String> availableCountries() {
        List<String> validCountries = new LinkedList<>(
                Arrays.asList(Locale
                        .getISOCountries()));
        String[] excluded = Config.get().getStringArray(
                ConfigDefaults.WEB_EXCLUDED_COUNTRIES);
        if (excluded != null) {
            validCountries.removeAll(new LinkedList<>(Arrays
                    .asList(excluded)));
        }
        SortedMap<String, String> ret = new TreeMap<>();
        for (String isoCountry : validCountries) {
            ret.put(this.getMessage(isoCountry), isoCountry);
        }

        return ret;
    }

    /**
     * Simple util method to determine if the
     * @param messageId we are searching for
     * @return boolean if we have loaded this message
     */
    public boolean hasMessage(String messageId) {
        return this.keyToBundleMap.containsKey(messageId);
    }

    /**
     * Get list of supported locales in string form
     * @return supported locales
     */
    public List<String> getSupportedLocales() {
        List<String> tmp = new LinkedList<>(this.supportedLocales.keySet());
        Collections.sort(tmp);
        return Collections.unmodifiableList(tmp);
    }

    /**
     * Returns the list of configured locales which is most likely a subset of
     * all the supported locales
     * @return list of configured locales
     */
    public List<String> getConfiguredLocales() {
        List<String> tmp = new LinkedList<>();
        for (String key : this.supportedLocales.keySet()) {
            LocaleInfo li = this.supportedLocales.get(key);
            if (!li.isAlias()) {
                tmp.add(key);
            }
        }
        Collections.sort(tmp);
        return Collections.unmodifiableList(tmp);
    }

    /**
     * Returns the list of installed documentation locales which is most likely a subset of
     * all the supported locales
     * @return list of installed documentation locales
     */
    public List<String> getInstalledDocsLocales() {
        List<String> tmp = new LinkedList<>();

        // Get locales of installed documentations
        File f = new File(DOC_FOLDER);
        String[] locales = f.list();
        if (locales != null) {
            tmp.addAll(Arrays.asList(locales));
            Collections.sort(tmp);
        }
        return Collections.unmodifiableList(tmp);
    }

    /**
     * Determines if locale is supported
     * @param locale user's locale
     * @return result
     */
    public boolean isLocaleSupported(Locale locale) {
        return this.supportedLocales.get(locale.toString()) != null;
    }

    /**
     * Determine the Timezone from the Context. Uses TimeZone.getDefault() if
     * there isn't one.
     *
     * @return TimeZone from the Context
     */
    public  TimeZone determineTimeZone() {
        TimeZone retval = null;
        Context ctx = Context.getCurrentContext();

        if (ctx != null) {
            retval = ctx.getTimezone();
        }
        if (retval == null) {
            log.debug("Context is null");
            // Get the app server's default timezone
            retval = TimeZone.getDefault();
        }
        if (log.isDebugEnabled()) {
            log.debug("Determined timeZone to be: {}", retval);
        }
        return retval;

    }

    /**
     * Returns a NEW instance of the collator/string comparator
     * based on the current locale..
     * Look at the javadoc for COllator to see what it does...
     * (basically an i18n aware string comparator)
     * @return neww instance of the collator
     */
    public Collator newCollator() {
        Context context = Context.getCurrentContext();
        if (context != null && context.getLocale() != null) {
            return Collator.getInstance(context.getLocale());
        }
        return Collator.getInstance();
    }

    /**
     * Return the default locale to use in the web UI
     * @return the default locale
     */
    private static Locale getDefaultLocale() {
        String defaultLocale = ConfigDefaults.get().getDefaultLocale();
        String[] localeParts = defaultLocale.split("_");
        switch (localeParts.length) {
            case 3:
                return new Locale(localeParts[0], localeParts[1], localeParts[2]);
            case 2:
                return new Locale(localeParts[0], localeParts[1]);
            default:
                return new Locale(defaultLocale);
        }
    }
}
