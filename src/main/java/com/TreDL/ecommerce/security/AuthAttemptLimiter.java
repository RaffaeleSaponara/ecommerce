package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.service.RegistrationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

/** Single-instance, bounded state. Locks protect bookkeeping, never password hashing or database work. */
public final class AuthAttemptLimiter {
    public static final int ACCOUNT_FAILURES=5, IP_FAILURES=30, SIGNUP_REQUESTS=10;
    private static final Duration WINDOW=Duration.ofMinutes(15);
    private final Clock clock;
    private final int accountCapacity, ipCapacity;
    private final Map<String,State> accounts=new HashMap<>(), ips=new HashMap<>(), signups=new HashMap<>();
    private Instant nextCleanup=Instant.MIN;

    public AuthAttemptLimiter(Clock clock) {this(clock,10_000,5_000);}
    AuthAttemptLimiter(Clock clock,int accountCapacity,int ipCapacity) {
        this.clock=Objects.requireNonNull(clock);this.accountCapacity=accountCapacity;this.ipCapacity=ipCapacity;
    }
    private static final class State {
        final Deque<Instant> events=new ArrayDeque<>();
        Instant blockedUntil;
        int pending;
        void expire(Instant now) {
            if(blockedUntil!=null && !now.isBefore(blockedUntil)) {blockedUntil=null;events.clear();}
            while(!events.isEmpty() && !events.peekFirst().isAfter(now.minus(WINDOW))) events.removeFirst();
        }
        boolean blocked(Instant now) {return blockedUntil!=null && now.isBefore(blockedUntil);}
        boolean empty() {return pending==0 && events.isEmpty() && blockedUntil==null;}
    }
    public static final class Ticket {
        private State account;
        private final State ip;
        private boolean finished;
        private Ticket(State account,State ip) {this.account=account;this.ip=ip;}
    }
    public static final class Limited extends RuntimeException {
        private final long retryAfter;
        Limited(long seconds) {super("Autenticazione temporaneamente limitata");retryAfter=Math.max(1,seconds);}
        public long retryAfter() {return retryAfter;}
    }
    public synchronized Ticket beginLogin(String email,String remoteAddress) {
        Instant now=clock.instant();cleanup(now,false);
        // Hash identity keys; do not retain supplied email addresses in the maps.
        String key=hash(RegistrationService.normalizeEmail(email));
        String ip=hash(remoteAddress==null?"unknown":remoteAddress);
        State account=state(accounts,key,accountCapacity,now);
        check(account,ACCOUNT_FAILURES,now);
        account.pending++; // Keep this state pinned if IP allocation needs capacity cleanup.
        try {
            State origin=state(ips,ip,ipCapacity,now);check(origin,IP_FAILURES,now);
            origin.pending++;
            return new Ticket(account,origin);
        } catch(RuntimeException error) {account.pending--;throw error;}
    }
    /** Called before delegating to Spring's normal success/failure handlers. */
    public synchronized boolean complete(Ticket ticket,boolean success) {
        if(ticket==null || ticket.finished) return false;
        ticket.finished=true;ticket.account.pending--;ticket.ip.pending--;
        Instant now=clock.instant();ticket.account.expire(now);ticket.ip.expire(now);
        if(success) {
            if(ticket.account.blocked(now) || ticket.ip.blocked(now)) return false;
            ticket.account.events.clear(); // Successful login never erases an IP's other failures.
            return true;
        }
        failed(ticket.account,ACCOUNT_FAILURES,now);failed(ticket.ip,IP_FAILURES,now);
        return !ticket.account.blocked(now) && !ticket.ip.blocked(now);
    }
    /** Resolve aliases using the real user lookup, including database collation equivalences. */
    synchronized void bindAccount(Ticket ticket,String canonicalEmail) {
        if(ticket==null || ticket.finished) return;
        Instant now=clock.instant();
        State canonical=state(accounts,hash(RegistrationService.normalizeEmail(canonicalEmail)),accountCapacity,now);
        if(canonical==ticket.account) return;
        check(canonical,ACCOUNT_FAILURES,now);
        canonical.pending++;ticket.account.pending--;ticket.account=canonical;
    }
    public synchronized void cancel(Ticket ticket) {
        if(ticket!=null && !ticket.finished) {ticket.finished=true;ticket.account.pending--;ticket.ip.pending--;}
    }
    public synchronized void signup(String remoteAddress) {
        Instant now=clock.instant();cleanup(now,false);
        State state=state(signups,hash(remoteAddress==null?"unknown":remoteAddress),ipCapacity,now);
        if(state.events.size()>=SIGNUP_REQUESTS) throw new Limited(seconds(now,state.events.peekFirst().plus(WINDOW)));
        state.events.addLast(now);
    }
    private void failed(State state,int limit,Instant now) {
        if(state.blocked(now)) return; // Rejected requests do not extend the block.
        state.events.addLast(now);
        if(state.events.size()>=limit) state.blockedUntil=now.plus(WINDOW);
    }
    private void check(State state,int limit,Instant now) {
        if(state.blocked(now)) throw new Limited(seconds(now,state.blockedUntil));
        if(state.events.size()+state.pending>=limit) throw new Limited(1);
    }
    private State state(Map<String,State> map,String key,int capacity,Instant now) {
        State state=map.get(key);
        if(state==null) {
            if(map.size()>=capacity) cleanup(now,true);
            if(map.size()>=capacity) throw new Limited(60); // Fail closed; never evict active limits.
            state=new State();map.put(key,state);
        }
        state.expire(now);return state;
    }
    private void cleanup(Instant now,boolean force) {
        if(!force && now.isBefore(nextCleanup)) return;
        for(Map<String,State> map:List.of(accounts,ips,signups))
            map.values().removeIf(state->{state.expire(now);return state.empty();});
        nextCleanup=now.plusSeconds(60);
    }
    private static long seconds(Instant from,Instant to) {return Math.max(1,(Duration.between(from,to).toMillis()+999)/1000);}
    private static String hash(String input) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
