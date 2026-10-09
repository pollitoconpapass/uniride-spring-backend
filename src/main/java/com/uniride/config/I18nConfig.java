package com.uniride.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

@Configuration
public class I18nConfig {

    public static final Locale LOCALE_ES_419 = Locale.forLanguageTag("es-419");
    public static final Locale LOCALE_EN_US = Locale.forLanguageTag("en-US");

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(LOCALE_ES_419);
        resolver.setSupportedLocales(List.of(
                LOCALE_ES_419,
                Locale.of("es"),
                LOCALE_EN_US,
                Locale.of("en")
        ));
        return resolver;
    }

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setDefaultLocale(LOCALE_ES_419);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}
