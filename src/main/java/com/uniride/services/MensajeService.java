package com.uniride.services;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
public class MensajeService {

    private final MessageSource messageSource;

    public MensajeService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String obtenerMensaje(String clave, Object... args) {
        return obtenerMensaje(clave, LocaleContextHolder.getLocale(), args);
    }

    public String obtenerMensaje(String clave, Locale locale, Object... args) {
        Locale localeEfectivo = locale != null ? locale : LocaleContextHolder.getLocale();
        try {
            return messageSource.getMessage(clave, args, localeEfectivo);
        } catch (NoSuchMessageException e) {
            return clave;
        }
    }

    public String resolverMensaje(String claveOTexto, Object... args) {
        return resolverMensaje(claveOTexto, LocaleContextHolder.getLocale(), args);
    }

    public String resolverMensaje(String claveOTexto, Locale locale, Object... args) {
        if (claveOTexto == null) {
            return null;
        }
        Locale localeEfectivo = locale != null ? locale : LocaleContextHolder.getLocale();
        try {
            return messageSource.getMessage(claveOTexto, args, localeEfectivo);
        } catch (NoSuchMessageException e) {
            return claveOTexto;
        }
    }
}
