package io.github.definitelystable.spongetube.medialab;

sealed interface RangeDecision permits RangeDecision.Full, RangeDecision.Partial, RangeDecision.Unsatisfiable {

    record Full() implements RangeDecision {
    }

    record Partial(long startInclusive, long endExclusive) implements RangeDecision {
        public Partial {
            if (startInclusive < 0 || endExclusive <= startInclusive) {
                throw new IllegalArgumentException("Invalid partial range");
            }
        }

        long length() {
            return endExclusive - startInclusive;
        }
    }

    record Unsatisfiable() implements RangeDecision {
    }
}
