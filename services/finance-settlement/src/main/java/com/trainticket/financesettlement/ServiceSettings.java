package com.trainticket.financesettlement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The read-path settings a deployment tunes without rebuilding the service.
 *
 * <p>They live in one file the release mounts and the process reads once, before
 * anything serves. Every setting carries its compiled default, so an environment
 * that mounts no file runs exactly what the contracts describe; the file is how
 * a deployment moves away from those defaults.
 *
 * <p>The file is read once and never again. Nothing on a request path touches
 * the disk, so a listing is answered against the values the process started
 * with, and a value a deployment changes takes effect with the process, not with
 * a request.
 */
public final class ServiceSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceSettings.class);

    /** Where the release mounts the settings file. */
    public static final String DEFAULT_SETTINGS_PATH = "/app/config/read-paths.properties";
    /** The variable that names another path, for a checkout or a side-by-side run. */
    public static final String SETTINGS_PATH_ENV = "FINANCE_SETTLEMENT_SETTINGS";

    /** How many entries a listing read returns at most. */
    public static final int DEFAULT_LISTING_PAGE_BOUND = 100;
    /** How many pages the daily reconciliation reads of the platform's settled refunds. */
    public static final int DEFAULT_PLATFORM_REFUND_PAGE_BOUND = 1_000;
    /** How many pages the reconciliation reads of one channel statement. */
    public static final int DEFAULT_CHANNEL_STATEMENT_PAGE_BOUND = 2_000;

    private final Map<String, String> values;

    private ServiceSettings(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /** The compiled defaults, with no file read. */
    public static ServiceSettings defaults() {
        return new ServiceSettings(Map.of());
    }

    /**
     * The settings file this process was pointed at, or the compiled defaults
     * when the environment names none and the release has mounted none.
     */
    public static ServiceSettings loadFromEnvironment() {
        String configured = System.getenv(SETTINGS_PATH_ENV);
        Path path = Path.of(configured == null || configured.isBlank() ? DEFAULT_SETTINGS_PATH : configured.trim());
        ServiceSettings settings = load(path);
        LOGGER.info(
            "finance settlement read paths: settings={} listingPageBound={} platformRefundPageBound={} channelStatementPageBound={}",
            Files.isRegularFile(path) ? path.toString() : "defaults",
            settings.listingPageBound(),
            settings.platformRefundPageBound(),
            settings.channelStatementPageBound());
        return settings;
    }

    /** Reads {@code path}, or answers the compiled defaults when it is not there. */
    public static ServiceSettings load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return defaults();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read finance settlement settings from " + path, error);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            values.put(name.trim(), properties.getProperty(name).trim());
        }
        return new ServiceSettings(values);
    }

    public int listingPageBound() {
        return positiveInt("read.listing.pageBound", DEFAULT_LISTING_PAGE_BOUND);
    }

    public int platformRefundPageBound() {
        return positiveInt("read.platformRefund.pageBound", DEFAULT_PLATFORM_REFUND_PAGE_BOUND);
    }

    public int channelStatementPageBound() {
        return positiveInt("read.channelStatement.pageBound", DEFAULT_CHANNEL_STATEMENT_PAGE_BOUND);
    }

    private int positiveInt(String name, int fallback) {
        String value = values.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                LOGGER.warn("finance settlement read paths: {}={} is not a positive count, using {}", name, value, fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException error) {
            LOGGER.warn("finance settlement read paths: {}={} is not a number, using {}", name, value, fallback);
            return fallback;
        }
    }
}
