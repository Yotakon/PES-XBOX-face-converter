package com.yotakon.pesport.core;

/** Error de conversion con un mensaje legible para el usuario. */
public class ConversionException extends RuntimeException {
    public ConversionException(String message) {
        super(message);
    }
}
