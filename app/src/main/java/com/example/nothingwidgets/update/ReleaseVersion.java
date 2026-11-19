package com.aurikqq.noswidgets.update;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative numeric stable-tag comparison; APK versionCode is still authoritative. */
public final class ReleaseVersion {
    private static final Pattern STABLE = Pattern.compile("^[vV]?([0-9]+(?:\\.[0-9]+)*)$");
    private static final Pattern INSTALLED = Pattern.compile("^[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");
    private ReleaseVersion() {}
    public static boolean isNewerStable(String tag, String installed) {
        if (tag == null || installed == null) return false;
        Matcher release = STABLE.matcher(tag.trim()), current = INSTALLED.matcher(installed.trim());
        if (!release.matches() || !current.matches()) return false;
        String[] a = release.group(1).split("\\."), b = current.group(1).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int comparison = new BigInteger(i < a.length ? a[i] : "0").compareTo(new BigInteger(i < b.length ? b[i] : "0"));
            if (comparison != 0) return comparison > 0;
        }
        return current.group(2) != null;
    }
}
