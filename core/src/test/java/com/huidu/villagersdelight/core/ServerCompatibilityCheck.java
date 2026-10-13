package com.huidu.villagersdelight.core;

import java.util.List;

public final class ServerCompatibilityCheck {
    public static void main(String[] args) throws Exception {
        var field = VillagersDelightPlugin.class.getDeclaredField("NMS_CANDIDATES");
        field.setAccessible(true);
        var candidates = (List<?>) field.get(null);
        for (String version : List.of("1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.11", "26.1.2", "26.2")) {
            assert matchingLayers(candidates, version) == 1 : version;
        }
        for (String version : List.of("1.21.4", "1.21.9", "1.21.10", "1.21.50", "1.21.110", "27.1", "")) {
            assert matchingLayers(candidates, version) == 0 : version;
        }
        assert matchingLayers(candidates, null) == 0;
        assert PickupEnabler.packMetadata("1.21.5-R0.1-SNAPSHOT").contains("\"pack_format\":71");
        assert PickupEnabler.packMetadata("1.21.6").contains("\"pack_format\":80");
        for (String version : List.of("1.21.7-R0.1-SNAPSHOT", "1.21.8")) {
            assert PickupEnabler.packMetadata(version).contains("\"pack_format\":81");
        }
        for (String version : List.of("1.21.9", "1.21.10", "1.21.11", "26.1.2", "26.2")) {
            assert PickupEnabler.packMetadata(version).contains("\"min_format\":88,\"max_format\":150");
        }
        System.out.println("VD version selection and pack metadata: 24 assertions passed");
    }

    private static long matchingLayers(List<?> candidates, String version) throws Exception {
        long matches = 0;
        for (Object candidate : candidates) {
            var method = candidate.getClass().getDeclaredMethod("matchesServer", String.class);
            method.setAccessible(true);
            if ((Boolean) method.invoke(candidate, version)) matches++;
        }
        return matches;
    }
}
