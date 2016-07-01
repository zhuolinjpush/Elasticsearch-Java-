package cn.jpush.util;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.jpush.utils.SystemConfig;

public class ClearESData {

    private static Logger logger = LoggerFactory.getLogger(ClearESData.class.getName());
    
    private static int SCROLLTIME = 60000;
    private Settings settings;
    private String esIndex;
    private String esType;
    private TransportClient client;
    private List<InetSocketTransportAddress> ISTAList;
    private String key;
    private long stime;
    private long etime;
    
    public ClearESData(String key, long stime, long etime) {
        this.key = key;
        this.stime = stime;
        this.etime = etime;
        this.esIndex = SystemConfig.getProperty(this.key + ".index");
        this.esType = SystemConfig.getProperty(this.key + ".indextype");
        prepareClientSettings();
        createClient();
    }
    
    private void prepareClientSettings() {
        this.settings = Settings.settingsBuilder()
                .put("client.transport.sniff", SystemConfig.getBooleanProperty(this.key + ".client.transport.sniff"))
                .put("client.transport.ping_timeout", SystemConfig.getProperty(this.key +  ".client.transport.ping_timeout"))
                .put("client.transport.nodes_sampler_interval", SystemConfig.getProperty(this.key + ".client.transport.nodes_sampler_interval"))
                .put("cluster.name", SystemConfig.getProperty(this.key + ".cluster.name")).build();
        
        this.ISTAList = new ArrayList<InetSocketTransportAddress>();
        String ipList = SystemConfig.getProperty(this.key + ".transport.ip");
        String[] ips = ipList.split(";");
        for (int i = 0; i < ips.length; i++) {
            String addr[] = ips[i].split(":");
            try {
                ISTAList.add(new InetSocketTransportAddress(InetAddress.getByName(addr[0]), Integer.valueOf(addr[1])));
            } catch (Exception e) {
                logger.error("add transport address error", e);
            }
        }
        logger.info("prepare es setting finish");
    }

    private void createClient() {
        try {
            this.client = TransportClient.builder().settings(this.settings).build();
            for (int i = 0; i < ISTAList.size(); i++) {
                logger.info(ISTAList.get(i) + "");
                client.addTransportAddress(ISTAList.get(i));
            }
        } catch (Exception e) {
            logger.error("init transport client error", e);
        }
        logger.info("create es client");
    }

    public void process() {
        try {
            
            QueryBuilder qb = QueryBuilders.rangeQuery("itime").from(this.stime).to(this.etime).includeLower(true).includeUpper(true);
            SearchResponse scrollResp = client.prepareSearch(this.esIndex)
                                           .setTypes(this.esType)
                                           .setScroll(new TimeValue(SCROLLTIME))
                                           .setQuery(qb)
                                           .setSize(2000)
                                           .execute()
                                           .actionGet();
            
            BulkProcessor processor = BulkProcessor.builder(client, new BulkProcessor.Listener() {
                
                @Override
                public void beforeBulk(long executionId, BulkRequest request) {
                    logger.info("before id=" + executionId);
                }
                
                @Override
                public void afterBulk(long executionId, BulkRequest request,
                        Throwable failure) {
                    logger.info("throw id=" + executionId + " " + failure.getMessage());
                }
                
                @Override
                public void afterBulk(long executionId, BulkRequest request,
                        BulkResponse response) {
                    logger.info("resp id=" + executionId + " done in "+ response.getTookInMillis()+ " ms");
                }
            })
            .setBulkActions(5000)
            .setBulkSize(new ByteSizeValue(200, ByteSizeUnit.MB))
            .setFlushInterval(TimeValue.timeValueSeconds(5))
            .setConcurrentRequests(1)
            .setBackoffPolicy(BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), 3))
            .build();
            
            long count = 0;
            while (true) {
                for (SearchHit hit : scrollResp.getHits().getHits()) {
                    count++;
                    processor.add(new DeleteRequest(this.esIndex, this.esType, hit.getId())); 
                    if (count % 1000 == 0) {
                        logger.info("processor " + count + " " + hit.getId());
                    }
                }
                scrollResp = client.prepareSearchScroll(scrollResp.getScrollId()).setScroll(new TimeValue(SCROLLTIME)).execute().actionGet();
                if (scrollResp.getHits().getHits().length == 0) {
                    logger.info("end processor " + count);
                    processor.close();
                    break;
                }
            }
            
        } catch (Exception e) {
            logger.error("process error", e);
            createClient();
        } finally {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            logger.warn("args error");
            return;
        }
        logger.info("args:" + args[0] + " " + args[1] + " " + args[2]);
        ClearESData obj = new ClearESData(args[0], Long.parseLong(args[1]), Long.parseLong(args[2]));
        obj.process();
    }

}
