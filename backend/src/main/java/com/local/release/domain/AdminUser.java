package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity @Table(name="sys_user")
public class AdminUser extends BaseEntity {
  @Column(nullable=false, unique=true, length=64) private String username;
  @Column(nullable=false, length=100) private String passwordHash;
  @Column(length=100) private String displayName;
  @Column(nullable=false) private boolean enabled=true;
  @Column(length=1000) private String menuPermissions="";
  private LocalDateTime lastLoginAt;
  protected AdminUser() {}
  public AdminUser(String username,String passwordHash){this.username=username;this.passwordHash=passwordHash;}
  public String getUsername(){return username;} @JsonIgnore public String getPasswordHash(){return passwordHash;}
  public void setPasswordHash(String v){passwordHash=v;}
  public String getDisplayName(){return displayName==null||displayName.isBlank()?username:displayName;} public void setDisplayName(String v){displayName=v;}
  public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
  public String getMenuPermissions(){return menuPermissions==null?"":menuPermissions;} public void setMenuPermissions(Collection<String> values){menuPermissions=values==null?"":values.stream().filter(Objects::nonNull).map(String::trim).filter(v->!v.isBlank()).distinct().collect(java.util.stream.Collectors.joining(","));}
  @JsonIgnore public Set<String> menuPermissionSet(){return getMenuPermissions().isBlank()?Set.of():new LinkedHashSet<>(Arrays.asList(getMenuPermissions().split(",")));}
  public LocalDateTime getLastLoginAt(){return lastLoginAt;} public void setLastLoginAt(LocalDateTime v){lastLoginAt=v;}
  public boolean isAdmin(){return "admin".equalsIgnoreCase(username);}
}
