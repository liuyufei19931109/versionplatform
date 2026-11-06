package com.local.release.config;

import com.local.release.domain.ModelEnums;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Hibernate update does not expand an existing H2 native ENUM when Java enum constants are added. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class EnumSchemaMigration implements HibernatePropertiesCustomizer {
  private final DataSource dataSource;
  public EnumSchemaMigration(DataSource dataSource){this.dataSource=dataSource;}
  private record Target(String table,String column,List<String> required){}
  private record Column(String schema,String table,String name,String type){}
  private static final List<Target> TARGETS=List.of(
    new Target("APP_SERVICE","SERVICE_TYPE",names(ModelEnums.ServiceType.values())),
    new Target("RELEASE_TASK","STATUS",names(ModelEnums.TaskStatus.values())),
    new Target("RELEASE_TASK","CURRENT_STEP",names(ModelEnums.StepType.values())),
    new Target("RELEASE_STEP","STEP_TYPE",names(ModelEnums.StepType.values())),
    new Target("RUNTIME_OPERATION","STATUS",names(ModelEnums.TaskStatus.values()))
  );
  @Override public void customize(Map<String,Object> hibernateProperties){
    try(Connection connection=dataSource.getConnection()){
      String product=connection.getMetaData().getDatabaseProductName();
      if("H2".equals(product))migrateH2(connection);
      else if("MySQL".equals(product)||"MariaDB".equals(product))verifyMysql(connection);
    }catch(SQLException error){throw new IllegalStateException("数据库枚举结构升级失败，平台已停止启动；请保留数据库并检查升级日志，不要清空数据",error);}
  }
  private void migrateH2(Connection connection)throws SQLException{
    for(Target target:TARGETS){
      Column column=findColumn(connection,target);if(column==null)continue; // A fresh database is created correctly by Hibernate later.
      if(!column.type().toUpperCase(Locale.ROOT).startsWith("ENUM("))continue; // Existing VARCHAR mappings accept new string values.
      List<String> existing=parseValues(column.type());LinkedHashSet<String> expanded=new LinkedHashSet<>(existing);expanded.addAll(target.required());
      if(expanded.size()==existing.size())continue;
      String values=expanded.stream().map(EnumSchemaMigration::literal).collect(Collectors.joining(","));
      String qualified=(column.schema()==null?"":identifier(column.schema())+".")+identifier(column.table());
      // SET DATA TYPE preserves default/nullability; retain every existing enum value and its order.
      try(Statement statement=connection.createStatement()){statement.execute("ALTER TABLE "+qualified+" ALTER COLUMN "+identifier(column.name())+" SET DATA TYPE ENUM("+values+")");}
      Column updated=findColumn(connection,target);
      if(updated==null||!new LinkedHashSet<>(parseValues(updated.type())).containsAll(expanded))throw new SQLException("Enum expansion verification failed: "+target.table()+"."+target.column());
    }
  }
  private Column findColumn(Connection connection,Target target)throws SQLException{
    DatabaseMetaData metadata=connection.getMetaData();String schema=connection.getSchema();
    try(ResultSet columns=metadata.getColumns(connection.getCatalog(),schema,"%","%")){
      while(columns.next())if(target.table().equalsIgnoreCase(columns.getString("TABLE_NAME"))&&target.column().equalsIgnoreCase(columns.getString("COLUMN_NAME")))return new Column(columns.getString("TABLE_SCHEM"),columns.getString("TABLE_NAME"),columns.getString("COLUMN_NAME"),columns.getString("TYPE_NAME"));
    }return null;
  }
  private void verifyMysql(Connection connection)throws SQLException{
    // Reconstructing MySQL MODIFY COLUMN can silently discard defaults/collation/comments. Do not guess.
    for(Target target:TARGETS)try(PreparedStatement statement=connection.prepareStatement("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND UPPER(TABLE_NAME)=? AND UPPER(COLUMN_NAME)=?")){
      statement.setString(1,target.table());statement.setString(2,target.column());
      try(ResultSet row=statement.executeQuery()){if(row.next()){
        String type=row.getString(1);if(type!=null&&type.toUpperCase(Locale.ROOT).startsWith("ENUM(")&&!new HashSet<>(parseValues(type)).containsAll(target.required()))throw new IllegalStateException("当前版本仅自动升级 H2 枚举结构；MySQL/MariaDB 请先由数据库管理员扩展 "+target.table()+"."+target.column()+" 枚举并保留现有值、默认值和约束，再启动平台");
      }}
    }
  }
  static List<String> parseValues(String type)throws SQLException{
    if(type==null||!type.toUpperCase(Locale.ROOT).startsWith("ENUM(")||!type.endsWith(")"))throw new SQLException("Unsupported enum metadata");
    String inside=type.substring(5,type.length()-1);Matcher matcher=Pattern.compile("\\s*'((?:''|[^'])*)'\\s*(?:,|$)").matcher(inside);List<String> values=new ArrayList<>();int end=0;
    while(matcher.find()){if(matcher.start()!=end)throw new SQLException("Unsupported enum metadata");values.add(matcher.group(1).replace("''","'"));end=matcher.end();}
    if(values.isEmpty()||end!=inside.length()||new HashSet<>(values).size()!=values.size())throw new SQLException("Unsupported enum metadata");return values;
  }
  private static String identifier(String value){return '"'+value.replace("\"","\"\"")+'"';}
  private static String literal(String value){return "'"+value.replace("'","''")+"'";}
  private static List<String> names(Enum<?>[] values){return Arrays.stream(values).map(Enum::name).toList();}
}
