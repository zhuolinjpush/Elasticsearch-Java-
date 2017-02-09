package cn.jpush.tool;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.jpush.utils.SystemConfig;

public class IKAnalyzerTest {

    private static Logger logger = LoggerFactory.getLogger(IKAnalyzerTest.class);
    
    private Settings settings;
    //private String _esIndex = null;
    private String _esIndexType = null;
    private TransportClient client = null;
    private List<InetSocketTransportAddress> ISTAList;
    private static String TAB = "\t";
    
    public IKAnalyzerTest() {
        _esIndexType = SystemConfig.getProperty("bjes.ik.indextype");
        prepareClientSetting();
        createClient();
    }

    private void prepareClientSetting() {
        this.settings = Settings.settingsBuilder()
                .put("client.transport.sniff", SystemConfig.getBooleanProperty("bjes.client.transport.sniff"))
                .put("client.transport.ping_timeout", SystemConfig.getProperty("bjes.client.transport.ping_timeout"))
                .put("client.transport.nodes_sampler_interval", SystemConfig.getProperty("bjes.client.transport.nodes_sampler_interval"))
                .put("cluster.name", SystemConfig.getProperty("bjes.cluster.name")).build();
        
        this.ISTAList = new ArrayList<InetSocketTransportAddress>();
        String ipList = SystemConfig.getProperty("bjes.transport.ip");
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
    
    public void query(String[] indices, String appkey, String keyword) {
        try {
            QueryBuilder queryBuilder = QueryBuilders.boolQuery().must(QueryBuilders.matchQuery("msg_content", keyword))
                                                                 .must(QueryBuilders.termQuery("appkey", appkey));
                                                                 //.must(QueryBuilders.rangeQuery("itime").gte(start_ts).lt(end_ts));
            SearchResponse response = client.prepareSearch(indices)
                                           .setTypes(this._esIndexType)
                                           .setQuery(queryBuilder)
                                           .setSize(1) //get 1
                                           .execute()
                                           .actionGet();
            //get max score
            float maxScore = response.getHits().getMaxScore();
            float minScore = maxScore * 0.7f;
            
            response = client.prepareSearch(indices)
                    .setTypes(this._esIndexType)
                    .setQuery(queryBuilder)
                    .setSize(1000) //get 1000
                    .setMinScore(minScore)
                    .execute()
                    .actionGet();
            
            int count = 0;
            for (SearchHit hit : response.getHits().getHits()) {
                logger.info( count++ + TAB + hit.getScore() + TAB + hit.getSourceAsString() );
            }

        } catch (Exception e) {
            logger.error("query es error", e);
        } finally {
            //
        }
        
    }
    
    public static void main(String[] args) {
        
        int startDay = Integer.parseInt(args[0]);//20170207
        int endDay = Integer.parseInt(args[1]);//20170208
        List<String> indicesList = new ArrayList<String>();
        for ( int i = startDay; i <= endDay; i++ ) {
            indicesList.add(String.valueOf(i));
        }
        // list to array
        String[] indices = new String[indicesList.size()];
        indicesList.toArray(indices);
        
        String appkey = args[2];
        String keyword = args[3];//会被分词
        //float min_score = Float.parseFloat(args[4]);
        
        IKAnalyzerTest analyzer = new IKAnalyzerTest();
        analyzer.query(indices, appkey, keyword);
        
    }

}
