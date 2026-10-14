package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CaseWorkConcurrencyTest {
    @Test void twoRealHttpWritersHaveOneCasWinnerAndSameKeyCreatesOnlyOneRevision()throws Exception{
        try(var f=new CaseApiFixture(false)){
            f.create();String path="/v2/adjudication/case-works/"+f.caseId+"/materials";
            var body=f.mapper.createObjectNode().put("expectedVersion",1).set("material",f.pack.path("materials").get(0));
            try(var pool=Executors.newFixedThreadPool(2)){
                var start=new CountDownLatch(1);var jobs=new ArrayList<Future<Integer>>();
                for(int i=0;i<2;i++)jobs.add(pool.submit(()->{start.await();return post(f,path,body.toString(),UUID.randomUUID().toString()).statusCode();}));
                start.countDown();var statuses=new ArrayList<Integer>();for(var result:jobs)statuses.add(result.get(60,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
                var second=f.mapper.createObjectNode().put("expectedVersion",2).set("material",f.pack.path("materials").get(1));String key=UUID.randomUUID().toString();
                var gate=new CountDownLatch(1);var results=new ArrayList<Future<HttpResponse<String>>>();
                for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();return post(f,path,second.toString(),key);}));gate.countDown();
                var left=results.get(0).get(60,TimeUnit.SECONDS);var right=results.get(1).get(60,TimeUnit.SECONDS);
                assertEquals(200,left.statusCode());assertEquals(200,right.statusCode());assertEquals(f.mapper.readTree(left.body()),f.mapper.readTree(right.body()));
            }
            assertEquals(3,f.current().path("version").asLong());assertEquals(3,f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_revision",Integer.class));
        }
    }
    private HttpResponse<String> post(CaseApiFixture f,String path,String body,String key)throws Exception{
        return f.member.send(HttpRequest.newBuilder(URI.create(f.endpoint+path)).header("Authorization","Bearer "+f.tokens.get("case-author"))
                .header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
