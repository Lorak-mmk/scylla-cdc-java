package com.scylladb.cdc.model.cql;

import com.scylladb.cdc.model.worker.RawChange;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public abstract class AbstractField {
    private final Object value;

    public AbstractField(Object value) {
        this.value = value;
    }

    public Object getAsObject() {
        return value;
    }

    public ByteBuffer getBytes() {
        return (ByteBuffer) value;
    }

    public String getString() {
        return (String) value;
    }

    public BigInteger getVarint() {
        return (BigInteger) value;
    }

    public BigDecimal getDecimal() {
        return (BigDecimal) value;
    }

    public UUID getUUID() {
        return (UUID) value;
    }

    public InetAddress getInet() {
        return (InetAddress) value;
    }

    public Float getFloat() {
        return (Float) value;
    }

    public Double getDouble() {
        return (Double) value;
    }

    public Long getLong() {
        return (Long) value;
    }

    public Integer getInt() {
        return (Integer) value;
    }

    public Short getShort() {
        return (Short) value;
    }

    public Byte getByte() {
        return (Byte) value;
    }

    public Boolean getBoolean() {
        return (Boolean) value;
    }

    public Map<Field, Field> getMap() {
        return (Map<Field, Field>) value;
    }

    public Set<Field> getSet() {
        return (Set<Field>) value;
    }

    public List<Field> getList() {
        return (List<Field>) value;
    }

    public Map<String, Field> getUDT() {
        return (Map<String, Field>) value;
    }

    public Date getTimestamp() {
        return (Date) value;
    }

    public Long getTime() {
        return (Long) value;
    }

    public RawChange.Duration getDuration() {
        return (RawChange.Duration) value;
    }

    public RawChange.CqlDate getDate() {
        return (RawChange.CqlDate) value;
    }

    @Override
    public String toString() {
        return Objects.toString(value);
    }
}
