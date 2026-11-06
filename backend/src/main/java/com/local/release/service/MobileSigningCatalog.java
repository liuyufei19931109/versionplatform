package com.local.release.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.io.Reader;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.naming.ldap.LdapName;

/** Read-only inventory of administrator-provisioned signing identities; no private material leaves this service. */
@Service
public class MobileSigningCatalog {
  @Value("${release.mobile.signing-root:}") String signingRoot;
  public record Profile(String name,String certificateSha256,boolean debugCertificate,boolean ready,String message){}
  public record Catalog(boolean configured,List<Profile> profiles){}
  public Catalog list(){
    if(signingRoot==null||signingRoot.isBlank())return new Catalog(false,List.of());
    try{
      Path root=Path.of(signingRoot).toAbsolutePath().normalize();
      FlutterBuildService.rejectSymlinks(root,root);
      if(!Files.isDirectory(root))return new Catalog(false,List.of());
      try(var files=Files.list(root)){
        List<Profile> profiles=files.filter(path->path.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}\\.properties"))
            .sorted().limit(100).map(path->inspect(root,path)).toList();
        return new Catalog(true,profiles);
      }
    }catch(Exception invalid){return new Catalog(false,List.of());}
  }
  private Profile inspect(Path root,Path file){
    String name=file.getFileName().toString().replaceFirst("\\.properties$","");
    char[] storePassword=null,keyPassword=null;
    try{
      privateFile(root,file,65536);
      Properties values=new Properties();try(Reader reader=Files.newBufferedReader(file)){values.load(reader);}
      for(String field:List.of("storeFile","storePassword","keyPassword","keyAlias"))if(values.getProperty(field)==null||values.getProperty(field).isBlank())throw new IllegalArgumentException();
      Path key=Path.of(values.getProperty("storeFile"));if(!key.isAbsolute())throw new IllegalArgumentException();key=key.normalize();privateFile(root,key,10*1024*1024);
      storePassword=values.getProperty("storePassword").toCharArray();keyPassword=values.getProperty("keyPassword").toCharArray();
      KeyStore store=KeyStore.getInstance(key.toFile(),storePassword);String alias=values.getProperty("keyAlias");
      if(!store.isKeyEntry(alias)||!(store.getKey(alias,keyPassword) instanceof PrivateKey)||!(store.getCertificate(alias) instanceof X509Certificate certificate))throw new IllegalArgumentException();
      certificate.checkValidity();
      String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
      boolean debug=new LdapName(certificate.getSubjectX500Principal().getName()).getRdns().stream().anyMatch(rdn->rdn.getType().equalsIgnoreCase("CN")&&String.valueOf(rdn.getValue()).equalsIgnoreCase("Android Debug"));
      return new Profile(name,fingerprint,debug,true,debug?"已登记的测试证书":"签名配置可用");
    }catch(Exception invalid){return new Profile(name,null,false,false,"签名配置不可用，请管理员检查文件权限、证书有效期和密钥配置");}
    finally{if(storePassword!=null)Arrays.fill(storePassword,'\0');if(keyPassword!=null)Arrays.fill(keyPassword,'\0');}
  }
  private static void privateFile(Path root,Path file,long maximum)throws Exception{
    FlutterBuildService.rejectSymlinks(root,file);
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>maximum)throw new IllegalArgumentException();
    if(Files.getFileStore(file).supportsFileAttributeView("posix")&&!Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE).containsAll(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS)))throw new IllegalArgumentException();
  }
}
