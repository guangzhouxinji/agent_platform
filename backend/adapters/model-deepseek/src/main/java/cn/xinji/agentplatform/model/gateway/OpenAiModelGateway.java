package cn.xinji.agentplatform.model.gateway;

import cn.xinji.agentplatform.runtime.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;

/** Single bounded HTTP transport. Resolvers must return currently approved, available configurations. */
public final class OpenAiModelGateway implements ModelGateway {
    public static final class Connection {
        private final Configuration configuration; private final String credential;
        public Connection(Configuration configuration,String credential) {
            this.configuration=Objects.requireNonNull(configuration);
            if(credential==null || credential.isBlank() || !credential.chars().allMatch(c->c>=33 && c<=126))
                throw new Failure("MODEL_CREDENTIAL_UNAVAILABLE");
            this.credential=credential;
        }
        public Configuration configuration() { return configuration; }
        @Override public String toString() { return "ModelConnection["+configuration.id()+", credential=REDACTED]"; }
    }
    private final Function<String,Connection> resolver; private final HttpClient http; private final ObjectMapper json;
    public OpenAiModelGateway(Function<String,Connection> resolver,ObjectMapper json) {
        this(resolver,json,HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    public OpenAiModelGateway(Function<String,Connection> resolver,ObjectMapper json,HttpClient http) {
        this.resolver=Objects.requireNonNull(resolver);this.json=json.copy();this.http=Objects.requireNonNull(http);
        if(http.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("MODEL_REDIRECTS_FORBIDDEN");
    }
    @Override public Result invoke(Call call) {
        Connection selected=resolver.apply(call.configurationId());
        if(selected==null)throw new Failure("MODEL_NOT_REGISTERED");
        Configuration config=selected.configuration;
        if(!config.id().equals(call.configurationId()) || !"OPENAI_CHAT_COMPLETIONS".equals(config.protocol())
                || !config.purposes().contains(call.purpose()) || !config.dataClasses().contains(call.dataClass()))
            throw new Failure("MODEL_PURPOSE_NOT_APPROVED");
        byte[] body=call.exactRequest().getBytes(StandardCharsets.UTF_8);
        if(body.length>config.maxInputBytes())throw new Failure("MODEL_INPUT_LIMIT");
        try {
            var input=json.readTree(body);
            if(!input.isObject() || !config.model().equals(input.path("model").asText()))throw new Failure("MODEL_REQUEST_NOT_APPROVED");
        } catch (Failure error) { throw error; }
        catch (Exception error) { throw new Failure("MODEL_REQUEST_NOT_APPROVED"); }
        var request=HttpRequest.newBuilder(config.endpoint()).timeout(config.timeout())
                .header("Authorization","Bearer "+selected.credential).header("Content-Type","application/json")
                .header("X-Client-Trace-Id",call.traceId()).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        long start=System.nanoTime();
        var future=http.sendAsync(request,info->new BoundedModelResponse(config.maxResponseBytes()));
        try {
            var response=future.get(config.timeout().toMillis(),TimeUnit.MILLISECONDS);
            int status=response.statusCode();
            if(status==429)throw new Failure("RATE_LIMITED",status);
            if(status==502 || status==503 || status==504)throw new Failure("UPSTREAM_UNAVAILABLE",status);
            if(status<200 || status>=300)throw new Failure("UPSTREAM_REJECTED_"+status,status);
            Connection current=resolver.apply(call.configurationId());
            if(current==null || !current.configuration.equals(config) || !current.credential.equals(selected.credential))
                throw new Failure("MODEL_CONFIGURATION_CHANGED");
            String raw;
            try { raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(response.body())).toString(); }
            catch (java.nio.charset.CharacterCodingException invalid) { throw new Failure("MODEL_RESPONSE_ENCODING_INVALID"); }
            return new Result(config.version(),status,raw,usage(raw),(System.nanoTime()-start)/1000000);
        } catch (TimeoutException timeout) { future.cancel(true);throw new Failure("TIMEOUT"); }
        catch (InterruptedException interrupted) { future.cancel(true);Thread.currentThread().interrupt();throw new Failure("INVOCATION_INTERRUPTED"); }
        catch (ExecutionException failure) {
            Throwable cause=failure.getCause();
            // Inspect every wrapper: HttpClient's request timer can win the race against
            // future.get, and reports HttpTimeoutException inside ExecutionException.
            while(cause!=null) {
                if(cause instanceof Failure known)throw known;
                if(cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
                    future.cancel(true);throw new Failure("TIMEOUT");
                }
                if(cause.getCause()==cause)break;
                cause=cause.getCause();
            }
            throw new Failure("UPSTREAM_UNAVAILABLE");
        }
    }
    private Usage usage(String raw) {
        try {
            var value=json.readTree(raw).path("usage");
            return new Usage(token(value.path("prompt_tokens")),token(value.path("completion_tokens")));
        } catch(Exception error) { return new Usage(null,null); }
    }
    private Long token(com.fasterxml.jackson.databind.JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong()>=0 ? value.asLong() : null;
    }
}
