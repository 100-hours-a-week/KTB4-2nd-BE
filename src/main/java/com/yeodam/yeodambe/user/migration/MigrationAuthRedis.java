package com.yeodam.yeodambe.user.migration;

import com.yeodam.yeodambe.user.security.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

@Component
@ConditionalOnProperty(name="migration.auth.enabled", havingValue="true")
public class MigrationAuthRedis {
    private final String settingsFile;
    private final ObjectMapper mapper;
    private final TokenHasher hasher;
    public MigrationAuthRedis(@Value("${migration.auth.redis-file}") String file,ObjectMapper mapper,TokenHasher hasher) {
        this.settingsFile=file;this.mapper=mapper;this.hasher=hasher;
    }
    private volatile Map<?,?> cachedConfig;
    private Map<?,?> config() {
        var value=cachedConfig;
        if(value==null) synchronized(this) {
            value=cachedConfig;
            if(value==null) cachedConfig=value=mapper.readValue(readSettings(),Map.class);
        }
        return value;
    }
    private String readSettings() {
        try { return Files.readString(Path.of(settingsFile)); }
        catch(IOException e) { throw new IllegalStateException("Redis verification configuration unavailable"); }
    }
    private final java.util.concurrent.ArrayBlockingQueue<Channel> idle = new java.util.concurrent.ArrayBlockingQueue<>(8);
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(8);
    private record Channel(SSLSocket socket, OutputStream out, InputStream in) {}
    @jakarta.annotation.PreDestroy
    void close() { Channel c;while((c=idle.poll())!=null) try { c.socket.close(); } catch(IOException ignored) {} }
    public boolean applied(MigrationAuthCommand command,MigrationAuthReply reply) {
        Channel channel=null;boolean acquired=false;boolean healthy=false;
        try {
            acquired=slots.tryAcquire(500,java.util.concurrent.TimeUnit.MILLISECONDS);
            if(!acquired) throw new IOException("Redis verification pool busy");
            Map<?,?> config=config();
            String prefix=(String)config.get("prefix");
            channel=idle.poll();
            if(channel==null) {
                String host=(String)config.get("host");
                SSLSocket socket=(SSLSocket)SSLSocketFactory.getDefault().createSocket();
                try {
                    socket.connect(new java.net.InetSocketAddress(host,((Number)config.get("port")).intValue()),1000);
                    socket.setSoTimeout(1000);
                    SSLParameters params=socket.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(params);
                    socket.startHandshake();
                    channel=new Channel(socket,socket.getOutputStream(),new BufferedInputStream(socket.getInputStream()));
                    send(channel.out,"AUTH",(String)config.get("password"));
                    if(!"OK".equals(read(channel.in))) throw new IOException("Redis verification authentication failed");
                } catch(Exception e) { socket.close();throw e; }
            }
            boolean matched;
            if(command.operation().equals("csrf")) {
                send(channel.out,"GET",prefix+"csrf:"+hasher.hash(command.credential()));
                matched=reply.csrfToken().equals(read(channel.in));
            } else {
                String hash=hasher.hash(reply.refreshToken());
                send(channel.out,"GET",prefix+"refresh:"+hash);String sid=read(channel.in);
                if(sid==null) matched=false;
                else { send(channel.out,"HGET",prefix+"session:"+sid,"refreshTokenHash");matched=hash.equals(read(channel.in)); }
            }
            healthy=true;return matched;
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Redis verification interrupted");
        } catch(Exception e) { throw new IllegalStateException("Redis verification transport failed: "+e.getClass().getSimpleName());
        } finally {
            if(channel!=null) { if(!healthy || !idle.offer(channel)) try {channel.socket.close();} catch(IOException ignored) {} }
            if(acquired) slots.release();
        }
    }
    private static void send(OutputStream out,String... values) throws IOException {
        out.write(("*"+values.length+"\r\n").getBytes(StandardCharsets.UTF_8));
        for(String v:values) { byte[] bytes=v.getBytes(StandardCharsets.UTF_8);
            out.write(("$"+bytes.length+"\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.write("\r\n".getBytes(StandardCharsets.UTF_8)); }
        out.flush();
    }
    private static String line(InputStream in) throws IOException {
        var b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1) { if(c==13) { if(in.read()!=10) throw new IOException();return b.toString(StandardCharsets.UTF_8); } b.write(c); }
        throw new EOFException();
    }
    private static String read(InputStream in) throws IOException {
        String head=line(in);if(head.startsWith("+")) return head.substring(1);
        if(!head.startsWith("$")) throw new IOException("Redis reply rejected");
        int n=Integer.parseInt(head.substring(1));if(n<0) return null;
        byte[] b=in.readNBytes(n);if(b.length!=n || in.read()!=13 || in.read()!=10) throw new EOFException();
        return new String(b,StandardCharsets.UTF_8);
    }
}
