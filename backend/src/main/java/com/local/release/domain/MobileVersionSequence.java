package com.local.release.domain;

import jakarta.persistence.*;

/** Durable high-water marks; failed or cancelled allocations are never returned to the sequence. */
@Entity @Table(name="mobile_version_sequence",uniqueConstraints=@UniqueConstraint(columnNames="sequence_key"))
public class MobileVersionSequence extends BaseEntity {
  @Column(name="sequence_key",nullable=false,length=320) private String sequenceKey;
  @Column(nullable=false) private long lastValue;
  protected MobileVersionSequence(){}
  public MobileVersionSequence(String key,long value){sequenceKey=key;lastValue=value;}
  public String getSequenceKey(){return sequenceKey;}
  public long getLastValue(){return lastValue;}
  public void advance(long value){if(value<lastValue)throw new IllegalArgumentException("版本序列不能回退");lastValue=value;}
}
