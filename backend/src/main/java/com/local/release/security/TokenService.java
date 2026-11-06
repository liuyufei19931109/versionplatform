package com.local.release.security;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
  public record SessionInfo(String username,boolean setupOnly){}
  private record Session(String username,Instant expiresAt,boolean setupOnly){}
  private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
  public String issue(String username){return issue(username,false);}
  public String issueSetup(String username){return issue(username,true);}
  private String issue(String username,boolean setupOnly){String token=UUID.randomUUID().toString();sessions.put(token,new Session(username,Instant.now().plusSeconds(setupOnly?15*60:12*3600),setupOnly));return token;}
  public SessionInfo validate(String token){Session s=sessions.get(token);if(s==null||s.expiresAt().isBefore(Instant.now())){sessions.remove(token);return null;}return new SessionInfo(s.username(),s.setupOnly());}
  public void revoke(String token){sessions.remove(token);}
  public void revokeUser(String username){sessions.entrySet().removeIf(entry->entry.getValue().username().equals(username));}
}
