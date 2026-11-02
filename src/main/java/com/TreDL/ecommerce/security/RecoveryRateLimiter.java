package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.service.RegistrationService;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Independent bounded windows: recovery never resets or consumes login/signup counters. */
@Component
public class RecoveryRateLimiter {
    private final Clock clock;
    private final Map<String,Deque<Instant>> emails=new HashMap<>(),ips=new HashMap<>(),resets=new HashMap<>();
    public RecoveryRateLimiter(Clock clock) {this.clock=clock;}
    public synchronized boolean email(String email) {return allow(emails,RegistrationService.normalizeEmail(email),3,10_000);}
    public synchronized boolean ip(String ip) {return allow(ips,ip==null?"unknown":ip,10,5_000);}
    public synchronized boolean resetIp(String ip) {return allow(resets,ip==null?"unknown":ip,30,5_000);}
    private boolean allow(Map<String,Deque<Instant>> map,String input,int limit,int capacity) {
        Instant now=clock.instant(),cutoff=now.minus(Duration.ofMinutes(15));
        String key=hash(input);
        Deque<Instant> events=map.get(key);
        if(events==null && map.size()>=capacity) {
            map.values().removeIf(q->{expire(q,cutoff);return q.isEmpty();});
            if(map.size()>=capacity) return false;
        }
        if(events==null) {events=new ArrayDeque<>();map.put(key,events);}
        expire(events,cutoff);
        if(events.size()>=limit)return false;
        events.addLast(now);return true;
    }
    private void expire(Deque<Instant> events,Instant cutoff) {while(!events.isEmpty() && !events.peekFirst().isAfter(cutoff))events.removeFirst();}
    private static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
