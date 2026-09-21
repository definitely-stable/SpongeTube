package io.github.definitelystable.spongetube.medialab;

final class RangeParser {

    private RangeParser() {
    }

    static RangeDecision parse(String rangeHeader, long completeLength) {
        if (completeLength < 0) {
            throw new IllegalArgumentException("completeLength must be >= 0");
        }
        if (rangeHeader == null || rangeHeader.isBlank() || completeLength == 0) {
            // RFC 9110 permits a server to ignore Range for a zero-length representation.
            return new RangeDecision.Full();
        }

        String value = rangeHeader.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) {
            return new RangeDecision.Full();
        }

        String spec = value.substring(6).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            return new RangeDecision.Full();
        }

        int dash = spec.indexOf('-');
        if (dash < 0 || dash != spec.lastIndexOf('-')) {
            return new RangeDecision.Full();
        }

        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();

        if (startText.isEmpty()) {
            Long suffixLength = parseUnsignedLong(endText);
            if (suffixLength == null) {
                return new RangeDecision.Full();
            }
            if (suffixLength == 0) {
                return new RangeDecision.Unsatisfiable();
            }
            long selectedLength = Math.min(suffixLength, completeLength);
            return new RangeDecision.Partial(completeLength - selectedLength, completeLength);
        }

        Long start = parseUnsignedLong(startText);
        if (start == null) {
            return new RangeDecision.Full();
        }

        if (start >= completeLength) {
            return new RangeDecision.Unsatisfiable();
        }

        if (endText.isEmpty()) {
            return new RangeDecision.Partial(start, completeLength);
        }

        Long endInclusive = parseUnsignedLong(endText);
        if (endInclusive == null) {
            return new RangeDecision.Full();
        }
        if (start > endInclusive) {
            // Invalid int-range, not a valid-but-unsatisfiable range.
            // M0-B deterministically ignores invalid Range syntax.
            return new RangeDecision.Full();
        }

        long clampedEndInclusive = Math.min(endInclusive, completeLength - 1);
        return new RangeDecision.Partial(start, clampedEndInclusive + 1);
    }

    private static Long parseUnsignedLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
