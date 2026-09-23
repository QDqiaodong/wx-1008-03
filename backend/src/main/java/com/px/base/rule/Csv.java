package com.px.base.rule;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 逗号分隔字段工具：证书的适用风级、锚点区域均用 CSV 存库。 */
public final class Csv {

    private Csv() {
    }

    public static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    public static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
    }

    public static List<String> missing(Set<String> coveredCsv, Set<String> required) {
        return required.stream()
                .filter(r -> !coveredCsv.contains(r))
                .sorted()
                .collect(Collectors.toList());
    }

    public static Set<String> toSet(String csv) {
        return new LinkedHashSet<>(split(csv));
    }
}
