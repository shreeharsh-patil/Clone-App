// Adapted from TeguFy/blackbox-android's Apache-2.0 CopyExecutor.
// Local experiment: preserve shared code and copy immutable APKs atomically.
package com.tegufy.blackbox.core.system.pm.installer;

import com.tegufy.blackbox.core.env.BEnvironment;
import com.tegufy.blackbox.core.system.pm.BPackageSettings;
import com.tegufy.blackbox.entity.pm.InstallOption;
import com.tegufy.blackbox.utils.NativeUtils;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipFile;

public class CopyExecutor implements Executor {
    private static byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    public static boolean sameApk(File source, File target) throws Exception {
        return source.length() == target.length() && Arrays.equals(digest(source), digest(target));
    }

    public static Map<String, File> sourceSet(File location) throws Exception {
        Map<String, File> files = new LinkedHashMap<>();
        if (location.isDirectory()) {
            File base = new File(location, "base.apk");
            if (!base.isFile()) throw new IOException("APK set has no base.apk");
            files.put("base.apk", base);
            File[] splits = location.listFiles((dir, name) -> name.endsWith(".apk") && !name.equals("base.apk"));
            if (splits == null) throw new IOException("Cannot read APK set");
            Arrays.sort(splits);
            for (File split : splits) files.put(split.getName(), split);
        } else {
            if (!location.isFile()) throw new IOException("APK does not exist");
            files.put("base.apk", location);
        }
        return files;
    }

    public static boolean supportsSource(File location) throws Exception {
        Set<String> abis = new HashSet<>();
        for (File file : sourceSet(location).values()) {
            try (ZipFile apk = new ZipFile(file)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("lib/") && name.endsWith(".so")) abis.add(name.split("/")[1]);
                }
            }
        }
        return abis.isEmpty() || abis.contains(android.os.Build.SUPPORTED_64_BIT_ABIS[0]);
    }

    public static boolean matchesImported(File location, String packageName) throws Exception {
        File base = BEnvironment.getBaseApkDir(packageName);
        if (!base.isFile()) return true;
        Map<String, File> incoming = sourceSet(location);
        Map<String, File> existing = sourceSet(base.getParentFile());
        if (!incoming.keySet().equals(existing.keySet())) return false;
        for (String name : incoming.keySet()) if (!sameApk(incoming.get(name), existing.get(name))) return false;
        return true;
    }

    private static void copyImmutable(File source, File target) throws Exception {
        if (target.isFile()) {
            if (!sameApk(source, target)) throw new IOException("A different APK is already imported");
            return;
        }
        File temporary = new File(target.getParentFile(), ".import-" + UUID.randomUUID() + ".tmp");
        try {
            byte[] expected = digest(source);
            try (InputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (!temporary.setReadOnly()) throw new IOException("Cannot make imported code read-only");
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
            if (!Arrays.equals(expected, digest(temporary))) throw new IOException("APK changed during import");
            if (!temporary.renameTo(target)) throw new IOException("Cannot publish imported APK");
        } finally { temporary.delete(); }
    }

    @Override public int exec(BPackageSettings ps, InstallOption option, int userId) {
        if (!option.isFlag(InstallOption.FLAG_STORAGE)) return -1;
        File source = new File(ps.pkg.baseCodePath);
        File target = BEnvironment.getBaseApkDir(ps.pkg.packageName);
        try {
            // Every virtual user of a package shares its code, not its data.
            // Never replace code underneath an existing clone during creation.
            ArrayList<File> sources = new ArrayList<>();
            sources.add(source);
            String[] splits = ps.pkg.splitCodePaths;
            String[] relocated = splits == null ? null : new String[splits.length];
            if (splits != null) {
                for (int i = 0; i < splits.length; i++) {
                    File split = new File(splits[i]);
                    sources.add(split);
                    relocated[i] = new File(target.getParentFile(), split.getName()).getAbsolutePath();
                }
            }
            boolean firstImport = !target.isFile();
            // Publish the base last so an incomplete first copy cannot look complete.
            for (int i = 1; i < sources.size(); i++) copyImmutable(sources.get(i), new File(relocated[i-1]));
            if (firstImport) for (File apk : sources) NativeUtils.copyNativeLib(apk, BEnvironment.getAppLibDir(ps.pkg.packageName));
            copyImmutable(source, target);
            ps.pkg.baseCodePath = target.getAbsolutePath();
            ps.pkg.splitCodePaths = relocated;
            ps.pkg.applicationInfo.sourceDir = target.getAbsolutePath();
            ps.pkg.applicationInfo.publicSourceDir = target.getAbsolutePath();
            ps.pkg.applicationInfo.splitSourceDirs = relocated;
            ps.pkg.applicationInfo.splitPublicSourceDirs = relocated;
            return 0;
        } catch (Exception error) {
            android.util.Log.e("RuntimeProof", "APK copy failed", error);
            return -1;
        }
    }
}
