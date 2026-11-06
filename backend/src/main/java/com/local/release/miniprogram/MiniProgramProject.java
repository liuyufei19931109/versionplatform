package com.local.release.miniprogram;

import com.local.release.domain.BaseEntity;
import jakarta.persistence.*;

@Entity @Table(name="mini_program_project")
public class MiniProgramProject extends BaseEntity {
  @Column(nullable=false,length=100) private String name;
  @Column(nullable=false,unique=true,length=80) private String code;
  @Column(nullable=false,unique=true,length=18) private String appId;
  @Column(nullable=false,length=500) private String gitRepo;
  @Column(nullable=false,length=120) private String defaultBranch;
  @Column(length=200) private String projectDirectory;
  private int robot=1;
  private boolean runChecks=true;
  private boolean enabled;
  public String getName(){return name;}
  public String getCode(){return code;}
  public String getAppId(){return appId;}
  public String getGitRepo(){return gitRepo;}
  public String getDefaultBranch(){return defaultBranch;}
  public String getProjectDirectory(){return projectDirectory;}
  public int getRobot(){return robot;}
  public boolean isRunChecks(){return runChecks;}
  public boolean isEnabled(){return enabled;}
  public MiniProgramConfiguration configuration(){return new MiniProgramConfiguration(name,code,appId,gitRepo,defaultBranch,projectDirectory,robot,runChecks,enabled);}
  public void apply(MiniProgramConfiguration c){name=c.name();code=c.code();appId=c.appId();gitRepo=c.gitRepo();defaultBranch=c.defaultBranch();projectDirectory=c.projectDirectory();robot=c.robot();runChecks=c.runChecks();enabled=c.enabled();}
}
