package com.local.release.miniprogram;

import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.security.MenuPermissions;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class MiniProgramAccess {
  private final AdminUserRepository users;
  public MiniProgramAccess(AdminUserRepository users){this.users=users;}
  public AdminUser require(String actor,boolean admin){
    AdminUser user=users.findByUsername(actor).filter(AdminUser::isEnabled).orElseThrow(()->new AccessDeniedException("请使用平台登录账号"));
    if(!user.isAdmin()&&(admin||!MenuPermissions.effective(user.menuPermissionSet()).contains("miniprograms")))throw new AccessDeniedException("没有小程序操作权限");
    return user;
  }
}
