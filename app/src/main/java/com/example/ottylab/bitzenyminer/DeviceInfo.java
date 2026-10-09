package com.example.ottylab.bitzenyminer;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeviceInfo {
    private static final Pattern CPU_DIRECTORY = Pattern.compile("cpu([0-9]+)");
    private static final Pattern CPU_RANGE = Pattern.compile("([0-9]+)(?:-([0-9]+))?");

    private DeviceInfo() { }

    static String cpuModel() {
        if (Build.VERSION.SDK_INT >= 31 && !Build.SOC_MODEL.isEmpty()
                && !"unknown".equalsIgnoreCase(Build.SOC_MODEL)) {
            return Build.SOC_MODEL;
        }

        String model = readCpuModelFromProc();
        if (model != null) return model;
        if (!Build.HARDWARE.isEmpty() && !"unknown".equalsIgnoreCase(Build.HARDWARE)) {
            return Build.HARDWARE;
        }
        return Build.DEVICE;
    }

    private static String readCpuModelFromProc() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            String fallback = null;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String value = line.substring(colon + 1).trim();
                if (value.isEmpty()) continue;
                if (lower.startsWith("hardware") || lower.startsWith("model name")
                        || lower.startsWith("soc model")) return value;
                if (lower.startsWith("processor") && fallback == null
                        && !value.matches("(?i)(0|[0-9]+|aarch64|armv[0-9].*)")) {
                    fallback = value;
                }
            }
            return fallback;
        } catch (IOException ignored) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    static int coreCount() {
        List<Integer> present = presentCpuIds();
        if (!present.isEmpty()) return present.size();
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, available);
    }

    static String coreSummary(Context context) {
        List<Integer> cpus = presentCpuIds();
        int total = cpus.isEmpty() ? coreCount() : cpus.size();
        Map<Long, Integer> groups = new TreeMap<>();
        for (int cpu : cpus) {
            long capacity = readLong("/sys/devices/system/cpu/cpu" + cpu + "/cpu_capacity");
            if (capacity <= 0) {
                capacity = readLong("/sys/devices/system/cpu/cpu" + cpu
                        + "/cpufreq/cpuinfo_max_freq");
            }
            if (capacity > 0) groups.put(capacity, groups.containsKey(capacity)
                    ? groups.get(capacity) + 1 : 1);
        }

        List<CoreGroup> clusters = mergeNearbyGroups(groups);
        if (clusters.size() < 2) {
            return context.getString(R.string.cpu_cores_topology_unknown, total);
        }
        Collections.sort(clusters, Comparator.comparingLong(group -> group.capacity));
        if (clusters.size() == 2) {
            return context.getString(R.string.cpu_cores_two_clusters,
                    clusters.get(0).count, clusters.get(1).count, total);
        }
        if (clusters.size() == 3) {
            return context.getString(R.string.cpu_cores_three_clusters,
                    clusters.get(0).count, clusters.get(1).count, clusters.get(2).count, total);
        }

        int low = clusters.get(0).count;
        int high = clusters.get(clusters.size() - 1).count;
        int mid = total - low - high;
        return context.getString(R.string.cpu_cores_three_clusters, low, mid, high, total);
    }

    private static List<CoreGroup> mergeNearbyGroups(Map<Long, Integer> groups) {
        List<CoreGroup> merged = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : groups.entrySet()) {
            if (merged.isEmpty()) {
                merged.add(new CoreGroup(entry.getKey(), entry.getValue()));
                continue;
            }
            CoreGroup last = merged.get(merged.size() - 1);
            long difference = entry.getKey() - last.capacity;
            // CPU frequencies can differ slightly inside a single cluster. Keep
            // those cores together while preserving distinct big.LITTLE groups.
            if (difference * 100L <= Math.max(1L, last.capacity) * 12L) {
                int count = last.count + entry.getValue();
                last.capacity = (last.capacity * last.count
                        + entry.getKey() * entry.getValue()) / count;
                last.count = count;
            } else {
                merged.add(new CoreGroup(entry.getKey(), entry.getValue()));
            }
        }
        return merged;
    }

    private static List<Integer> presentCpuIds() {
        String present = readFirstLine("/sys/devices/system/cpu/present");
        List<Integer> ids = parseCpuRange(present);
        if (!ids.isEmpty()) return ids;

        File[] files = new File("/sys/devices/system/cpu").listFiles();
        if (files != null) {
            for (File file : files) {
                Matcher matcher = CPU_DIRECTORY.matcher(file.getName());
                if (matcher.matches()) ids.add(Integer.parseInt(matcher.group(1)));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private static List<Integer> parseCpuRange(String range) {
        List<Integer> ids = new ArrayList<>();
        if (range == null) return ids;
        for (String part : range.split(",")) {
            Matcher matcher = CPU_RANGE.matcher(part.trim());
            if (!matcher.matches()) continue;
            int first = Integer.parseInt(matcher.group(1));
            int last = matcher.group(2) == null ? first : Integer.parseInt(matcher.group(2));
            for (int id = first; id <= last && id - first < 512; id++) ids.add(id);
        }
        return ids;
    }

    static String cpuTemperature() {
        File thermalRoot = new File("/sys/class/thermal");
        File[] zones = thermalRoot.listFiles(file -> file.getName().startsWith("thermal_zone"));
        if (zones == null) return null;

        double highestCpuTemperature = Double.NEGATIVE_INFINITY;
        for (File zone : zones) {
            String type = readFirstLine(new File(zone, "type").getAbsolutePath());
            if (type == null) continue;
            String lowerType = type.toLowerCase(Locale.ROOT);
            if (!(lowerType.contains("cpu") || lowerType.contains("soc")
                    || lowerType.contains("ap-") || lowerType.contains("tsens")
                    || lowerType.contains("cluster"))) continue;

            String value = readFirstLine(new File(zone, "temp").getAbsolutePath());
            if (value == null) continue;
            try {
                double temperature = Double.parseDouble(value.trim());
                if (Math.abs(temperature) > 1000.0) temperature /= 1000.0;
                if (temperature >= -20.0 && temperature <= 150.0) {
                    highestCpuTemperature = Math.max(highestCpuTemperature, temperature);
                }
            } catch (NumberFormatException ignored) { }
        }

        if (highestCpuTemperature == Double.NEGATIVE_INFINITY) return null;
        return String.format(Locale.getDefault(), "%.1f °C", highestCpuTemperature);
    }

    private static long readLong(String path) {
        String value = readFirstLine(path);
        if (value == null) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String readFirstLine(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            return reader.readLine();
        } catch (IOException | SecurityException ignored) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader == null) return;
        try {
            reader.close();
        } catch (IOException ignored) { }
    }

    private static final class CoreGroup {
        long capacity;
        int count;
        CoreGroup(long capacity, int count) {
            this.capacity = capacity;
            this.count = count;
        }
    }
}
