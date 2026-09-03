package com.huanbao.dataquery.core.spi;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 领域 SPI 注册表。支持显式注册和标准 Java ServiceLoader 发现两种装配方式。
 */
public final class DomainSemanticProviderRegistry {

    private final Map<String, DomainSemanticProvider> providers = new LinkedHashMap<>();

    public DomainSemanticProviderRegistry(Collection<? extends DomainSemanticProvider> initialProviders) {
        Objects.requireNonNull(initialProviders, "initialProviders").forEach(this::register);
    }

    public static DomainSemanticProviderRegistry discover() {
        return new DomainSemanticProviderRegistry(
                ServiceLoader.load(DomainSemanticProvider.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList());
    }

    public void register(DomainSemanticProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String domainCode = normalizeCode(provider.getDomainCode());
        if (providers.putIfAbsent(domainCode, provider) != null) {
            throw new IllegalStateException("Duplicate domain semantic provider: " + domainCode);
        }
    }

    public Optional<DomainSemanticProvider> find(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(normalizeCode(domainCode)));
    }

    public DomainSemanticProvider require(String domainCode) {
        return find(domainCode).orElseThrow(() ->
                new IllegalArgumentException("No domain semantic provider: " + domainCode));
    }

    public Map<String, DomainSemanticProvider> view() {
        return Collections.unmodifiableMap(providers);
    }

    private static String normalizeCode(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) {
            throw new IllegalArgumentException("Domain code must not be blank");
        }
        return domainCode.trim().toUpperCase(Locale.ROOT);
    }
}
