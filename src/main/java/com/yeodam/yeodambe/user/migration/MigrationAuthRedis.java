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
    public boolean applied(MigrationAuthCommand command,MigrationAuthReply reply) {
        try {
            Map<?,?> config=mapper.readValue(Files.readString(Path.of(settingsFile)),Map.class);
            String host=(String)config.get("host"),prefix=(String)config.get("prefix");
            try(SSLSocket socket=(SSLSocket)SSLSocketFactory.getDefault().createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host,((Number)config.get("port")).intValue()),1000);
                socket.setSoTimeout(1000);
                SSLParameters params=socket.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(params);
                socket.startHandshake();
                var out=socket.getOutputStream();var in=new BufferedInputStream(socket.getInputStream());
                send(out,"AUTH",(String)config.get("password"));if(!"OK".equals(read(in))) return false;
                if(command.operation().equals("csrf")) {
                    send(out,"GET",prefix+"csrf:"+hasher.hash(command.credential()));
                    return reply.csrfToken().equals(read(in));
                }
                String hash=hasher.hash(reply.refreshToken());
                send(out,"GET",prefix+"refresh:"+hash);String sid=read(in);
                if(sid==null) return false;
                send(out,"HGET",prefix+"session:"+sid,"refreshTokenHash");
                return hash.equals(read(in));
            }
        } catch(Exception e) { return false; }
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
