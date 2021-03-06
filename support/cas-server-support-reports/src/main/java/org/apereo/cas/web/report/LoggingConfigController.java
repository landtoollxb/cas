package org.apereo.cas.web.report;

import com.google.common.base.Throwables;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.MemoryMappedFileAppender;
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.slf4j.Log4jLoggerFactory;
import org.apereo.cas.audit.spi.DelegatingAuditTrailManager;
import org.apereo.inspektr.audit.AuditActionContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.mvc.AbstractNamedMvcEndpoint;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Controller to handle the logging dashboard requests.
 *
 * @author Misagh Moayyed
 * @since 4.2
 */
public class LoggingConfigController extends AbstractNamedMvcEndpoint {
    private static final String VIEW_CONFIG = "monitoring/viewLoggingConfig";
    private static final String LOGGER_NAME_ROOT = "root";

    private LoggerContext loggerContext;

    private final DelegatingAuditTrailManager auditTrailManager;

    @Autowired
    private Environment environment;

    @Autowired
    private ResourceLoader resourceLoader;

    private Resource logConfigurationFile;

    public LoggingConfigController(final DelegatingAuditTrailManager auditTrailManager) {
        super("casloggingconfig", "/logging", true, true);
        this.auditTrailManager = auditTrailManager;
    }

    /**
     * Init. Attempts to locate the logging configuration to insert listeners.
     * The log configuration location is pulled directly from the environment
     * given there is not an explicit property mapping for it provided by Boot, etc.
     */
    @PostConstruct
    public void initialize() {
        try {
            final String logFile = environment.getProperty("logging.config");
            this.logConfigurationFile = this.resourceLoader.getResource(logFile);

            this.loggerContext = Configurator.initialize("CAS", null, this.logConfigurationFile.getURI());
            this.loggerContext.getConfiguration().addListener(reconfigurable -> loggerContext.updateLoggers(reconfigurable.reconfigure()));
        } catch (final Exception e) {
            throw Throwables.propagate(e);
        }
    }

    /**
     * Gets default view.
     *
     * @return the default view
     * @throws Exception the exception
     */
    @GetMapping
    public ModelAndView getDefaultView() throws Exception {
        final Map<String, Object> model = new HashMap<>();
        model.put("logConfigurationFile", logConfigurationFile.getURI().toString());
        return new ModelAndView(VIEW_CONFIG, model);
    }

    /**
     * Gets active loggers.
     *
     * @param request  the request
     * @param response the response
     * @return the active loggers
     * @throws Exception the exception
     */
    @GetMapping(value = "/getActiveLoggers")
    @ResponseBody
    public Map<String, Object> getActiveLoggers(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        final Map<String, Object> responseMap = new HashMap<>();
        final Map<String, Logger> loggers = getActiveLoggersInFactory();
        responseMap.put("activeLoggers", loggers.values());
        return responseMap;
    }


    /**
     * Gets configuration as JSON.
     * Depends on the log4j core API.
     *
     * @param request  the request
     * @param response the response
     * @return the configuration
     * @throws Exception the exception
     */
    @GetMapping(value = "/getConfiguration")
    @ResponseBody
    public Map<String, Object> getConfiguration(final HttpServletRequest request, final HttpServletResponse response) throws Exception {

        final Collection<Map<String, Object>> configuredLoggers = new HashSet<>();
        getLoggerConfigurations().forEach(config -> {
            final Map<String, Object> loggerMap = new HashMap<>();
            loggerMap.put("name", StringUtils.defaultIfBlank(config.getName(), LOGGER_NAME_ROOT));
            loggerMap.put("state", config.getState());
            if (config.getProperties() != null) {
                loggerMap.put("properties", config.getProperties());
            }
            loggerMap.put("additive", config.isAdditive());
            loggerMap.put("level", config.getLevel().name());
            final Collection<String> appenders = new HashSet<>();
            config.getAppenders().keySet().stream().map(key -> config.getAppenders().get(key)).forEach(appender -> {
                final ToStringBuilder builder = new ToStringBuilder(this, ToStringStyle.JSON_STYLE);
                builder.append("name", appender.getName());
                builder.append("state", appender.getState());
                builder.append("layoutFormat", appender.getLayout().getContentFormat());
                builder.append("layoutContentType", appender.getLayout().getContentType());
                if (appender instanceof FileAppender) {
                    builder.append("file", ((FileAppender) appender).getFileName());
                    builder.append("filePattern", "(none)");
                }
                if (appender instanceof RandomAccessFileAppender) {
                    builder.append("file", ((RandomAccessFileAppender) appender).getFileName());
                    builder.append("filePattern", "(none)");
                }
                if (appender instanceof RollingFileAppender) {
                    builder.append("file", ((RollingFileAppender) appender).getFileName());
                    builder.append("filePattern", ((RollingFileAppender) appender).getFilePattern());
                }
                if (appender instanceof MemoryMappedFileAppender) {
                    builder.append("file", ((MemoryMappedFileAppender) appender).getFileName());
                    builder.append("filePattern", "(none)");
                }
                if (appender instanceof RollingRandomAccessFileAppender) {
                    builder.append("file", ((RollingRandomAccessFileAppender) appender).getFileName());
                    builder.append("filePattern", ((RollingRandomAccessFileAppender) appender).getFilePattern());
                }
                appenders.add(builder.build());
            });
            loggerMap.put("appenders", appenders);
            configuredLoggers.add(loggerMap);
        });
        final Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("loggers", configuredLoggers);
        return responseMap;
    }

    private Map<String, Logger> getActiveLoggersInFactory() {
        final Log4jLoggerFactory factory = (Log4jLoggerFactory) getCasLoggerFactoryInstance();
        if (factory != null) {
            return factory.getLoggersInContext(this.loggerContext);
        }
        return new HashMap<>();
    }

    private static ILoggerFactory getCasLoggerFactoryInstance() {
        return LoggerFactory.getILoggerFactory();
    }

    /**
     * Gets logger configurations.
     *
     * @return the logger configurations
     * @throws IOException the iO exception
     */
    private Set<LoggerConfig> getLoggerConfigurations() throws IOException {
        final Configuration configuration = this.loggerContext.getConfiguration();
        return new HashSet<>(configuration.getLoggers().values());
    }

    /**
     * Looks up the logger in the logger factory,
     * and attempts to find the real logger instance
     * based on the underlying logging framework
     * and retrieve the logger object. Then, updates the level.
     * This functionality at this point is heavily dependant
     * on the log4j API.
     *
     * @param loggerName  the logger name
     * @param loggerLevel the logger level
     * @param additive    the additive nature of the logger
     * @param request     the request
     * @param response    the response
     * @throws Exception the exception
     */
    @PostMapping(value = "/updateLoggerLevel")
    @ResponseBody
    public void updateLoggerLevel(@RequestParam final String loggerName,
                                  @RequestParam final String loggerLevel,
                                  @RequestParam(defaultValue = "false") final boolean additive,
                                  final HttpServletRequest request,
                                  final HttpServletResponse response)
            throws Exception {

        final Collection<LoggerConfig> loggerConfigs = getLoggerConfigurations();
        loggerConfigs.stream().
                filter(cfg -> cfg.getName().equals(loggerName))
                .forEachOrdered(cfg -> {
                    cfg.setLevel(Level.getLevel(loggerLevel));
                    cfg.setAdditive(additive);
                });
        this.loggerContext.updateLoggers();
    }

    /**
     * Gets audit log.
     *
     * @param request  the request
     * @param response the response
     * @return the audit log
     * @throws Exception the exception
     */
    @GetMapping(value = "/getAuditLog")
    @ResponseBody
    public Set<AuditActionContext> getAuditLog(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        return this.auditTrailManager.get();
    }
}
