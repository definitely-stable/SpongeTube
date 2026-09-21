package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;

final class NoProgressCancelledException extends IOException {
    NoProgressCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
