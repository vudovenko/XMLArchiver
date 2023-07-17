package ru.incordmed.exceptions;

import java.io.IOException;

public class MissingPropertiesFileException extends IOException {

    public MissingPropertiesFileException(String message) {
        super(message);
    }
}
