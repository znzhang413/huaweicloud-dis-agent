package com.huaweicloud.dis.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.huaweicloud.dis.*;
import com.huaweicloud.dis.agent.config.AgentConfiguration;
import com.huaweicloud.dis.agent.config.Configuration;
import com.huaweicloud.dis.agent.config.ConfigurationException;
import com.huaweicloud.dis.agent.metrics.IMetricsContext;
import com.huaweicloud.dis.agent.metrics.IMetricsScope;
import com.huaweicloud.dis.agent.metrics.Metrics;
import com.huaweicloud.dis.agent.processing.utils.WCCTool;
import com.huaweicloud.dis.agent.tailing.FileFlow;
import com.huaweicloud.dis.agent.tailing.FileFlowFactory;
import com.huaweicloud.dis.core.DISCredentials;

import lombok.Getter;

/**
 * Global context for the agent, including configuration, caches and transient state.
 */
public class AgentContext extends AgentConfiguration implements IMetricsContext
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentContext.class);
    
    @VisibleForTesting
    static final String DEFAULT_USER_AGENT = "dis-agent";
    
    @VisibleForTesting
    public final FileFlowFactory fileFlowFactory;
    
    /**
     * The listing of flows, ordered in order of appearance in configuration
     */
    private final Map<String, FileFlow<?>> flows = new LinkedHashMap<>();
    
    private DIS disClient;
    
    private DISAsync disClientAsync;
    
    private IMetricsContext metrics;
    
    @Getter
    private final DISCredentials credentials;
    
    /**
     * @param configuration
     * @throws ConfigurationException
     */
    public AgentContext(Configuration configuration)
    {
        this(configuration, new FileFlowFactory());
    }
    
    /**
     * @param configuration
     * @param fileFlowFactory
     * @throws ConfigurationException
     */
    public AgentContext(Configuration configuration, FileFlowFactory fileFlowFactory)
    {
        super(configuration);
        credentials = initCredentials();
        this.fileFlowFactory = fileFlowFactory;
        if (containsKey("flows"))
        {
            for (Configuration c : readList("flows", Configuration.class))
            {
                FileFlow<?> flow = fileFlowFactory.getFileFlow(this, c);
                if (!flow.isEnable())
                {
                    LOGGER.warn("Flow [{}] is not enable.", flow.getId());
                    continue;
                }
                if (flows.containsKey(flow.getId()))
                    throw new ConfigurationException("Duplicate flow: " + flow.getId());
                flows.put(flow.getId(), flow);
            }
        }
    }
    
    /**
     * @return A new instance of a threadpool executor for sending data to destination.
     */
    public ThreadPoolExecutor createSendingExecutor()
    {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("sender-%03d").build();
        ThreadPoolExecutor tp =
            new ThreadPoolExecutor(maxSendingThreads(), maxSendingThreads(), sendingThreadsKeepAliveMillis(),
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(sendingThreadsMaxQueueSize()), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        tp.allowCoreThreadTimeOut(true);
        return tp;
    }
    
    public ThreadPoolExecutor createFlowSendingExecutor(FileFlow fileFlow)
    {
        ThreadFactory threadFactory =
            new ThreadFactoryBuilder().setNameFormat("sender-%03d-" + fileFlow.getId()).build();
        ThreadPoolExecutor tp = new ThreadPoolExecutor(fileFlow.getSendingThreadSize(), fileFlow.getSendingThreadSize(),
            sendingThreadsKeepAliveMillis(), TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(sendingThreadsMaxQueueSize()), threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
        tp.allowCoreThreadTimeOut(true);
        return tp;
    }
    
    public synchronized DIS getDISClient()
    {
        if (disClient == null)
        {
            disClient = new DISClient(getDISConfig());
        }
        return disClient;
    }
    
    public synchronized DISAsync getDISClientAsync()
    {
        if (disClientAsync == null)
        {
            disClientAsync = new DISClientAsync(getDISConfig(), null);
        }
        return disClientAsync;
    }
    
    private synchronized IMetricsContext getMetricsContext()
    {
        if (metrics == null)
        {
            metrics = new Metrics(this);
        }
        return metrics;
    }
    
    public synchronized FileFlow<?> flow(String flowId)
    {
        return flows.get(flowId);
    }
    
    public synchronized List<FileFlow<?>> flows()
    {
        return new ArrayList<>(flows.values());
    }
    
    @Override
    public IMetricsScope beginScope()
    {
        return getMetricsContext().beginScope();
    }
    
    protected DISCredentials initCredentials()
    {
        Preconditions.checkArgument(getConfigMap().get(DISConfig.PROPERTY_AK) != null, "ak should not be null");
        Preconditions.checkArgument(getConfigMap().get(DISConfig.PROPERTY_SK) != null, "sk should not be null");
        
        String ak = tryGetDecryptValue(DISConfig.PROPERTY_AK);
        String sk = tryGetDecryptValue(DISConfig.PROPERTY_SK);
        String securityToken = tryGetDecryptValue(DISConfig.PROPERTY_SECURITY_TOKEN);
        String dataPassword = tryGetDecryptValue(DISConfig.PROPERTY_DATA_PASSWORD);
        
        return new DISCredentials(ak, sk, securityToken, dataPassword);
    }
    
    protected String tryGetDecryptValue(String key)
    {
        Object v = getConfigMap().get(key);
        if (v == null)
        {
            return null;
        }
        String value = String.valueOf(v);
        // value is too long, and start with "d2NjX", it maybe encrypted.
        if (value.length() > 200 && value.startsWith("d2NjX"))
        {
            try
            {
                LOGGER.info("Try to decrypt [{}].", key);
                return WCCTool.getInstance().decrypt(value);
            }
            catch (Exception e)
            {
                LOGGER.error("Failed to decrypt [{}].", key);
                throw e;
            }
        }
        return value;
    }
    
    public DISConfig getDISConfig()
    {
        DISConfig disConfig = DISConfig.buildConfig((String)null);
        for (Map.Entry<String, Object> next : getConfigMap().entrySet())
        {
            if (next.getValue() != null)
            {
                disConfig.put(next.getKey(), next.getValue().toString());
            }
        }
        
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_REGION_ID, getConfigMap().get(CONFIG_REGION_KEY), true);
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_AK, credentials.getAccessKeyId(), true);
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_SK, credentials.getSecretKey(), true);
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_PROJECT_ID, getConfigMap().get(CONFIG_PROJECTID_KEY), true);
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_ENDPOINT, disEndpoint(), false);
        updataDisConfigParam(disConfig,
            DISConfig.PROPERTY_IS_DEFAULT_DATA_ENCRYPT_ENABLED,
            getConfigMap().get(CONFIG_DATA_ENCRYPT_ENABLED_KEY),
            false);
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_DATA_PASSWORD, credentials.getDataPassword(), false);
        updataDisConfigParam(disConfig,
            DISConfig.PROPERTY_CONNECTION_TIMEOUT,
            getConfigMap().get(CONFIG_CONNECTION_TIMEOUT_KEY),
            false);
        updataDisConfigParam(disConfig,
            DISConfig.PROPERTY_SOCKET_TIMEOUT,
            getConfigMap().get(CONFIG_SOCKET_TIMEOUT_KEY),
            false);
        updataDisConfigParam(disConfig,
            DISConfig.PROPERTY_MAX_PER_ROUTE,
            getConfigMap().get(CONFIG_DEFAULT_MAX_PER_ROUTE_KEY),
            false);
        updataDisConfigParam(disConfig,
            DISConfig.PROPERTY_MAX_TOTAL,
            getConfigMap().get(CONFIG_DEFAULT_MAX_TOTAL_KEY),
            false);
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_IS_DEFAULT_TRUSTED_JKS_ENABLED, "false", false);
        // no need specify config.provider.class
        disConfig.remove(DISConfig.PROPERTY_CONFIG_PROVIDER_CLASS);
        updataDisConfigParam(disConfig,
            DISConfig.PROPERTY_BODY_SERIALIZE_TYPE,
            getConfigMap().get(CONFIG_BODY_SERIALIZE_TYPE_KEY),
            false);
        updataDisConfigParam(disConfig, DISConfig.PROPERTY_SECURITY_TOKEN, credentials.getSecurityToken(), false);
        return disConfig;
    }
    
    private void updataDisConfigParam(DISConfig disConfig, String param, Object value, boolean isRequired)
    {
        if (value == null)
        {
            if (isRequired)
            {
                throw new IllegalArgumentException("param [" + param + "] is null.");
            }
            return;
        }
        disConfig.set(param, value.toString());
    }
}