package org.astarteplatform.devicesdk.generic;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.table.TableUtils;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.astarteplatform.devicesdk.AstartePropertyStorage;
import org.astarteplatform.devicesdk.AstartePropertyStorageException;
import org.astarteplatform.devicesdk.protocol.AstarteInterface;
import org.bson.*;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.joda.time.DateTime;

class AstarteGenericPropertyStorage implements AstartePropertyStorage {
  private final BSONDecoder mBSONDecoder = new BasicBSONDecoder();
  private final BSONCallback mBSONCallback = new BasicBSONCallback();
  private Dao<AstarteGenericPropertyEntry, String> mPropertyEntryDao;

  AstarteGenericPropertyStorage(Dao<AstarteGenericPropertyEntry, String> propertyEntryDao)
      throws AstartePropertyStorageException {
    try {
      TableUtils.createTableIfNotExists(
          propertyEntryDao.getConnectionSource(), AstarteGenericPropertyEntry.class);
    } catch (SQLException e) {
      throw new AstartePropertyStorageException("Cannot create property storage table", e);
    }
    mPropertyEntryDao = propertyEntryDao;
  }

  @Override
  public List<String> getStoredPathsForInterface(String astarteInterfaceName)
      throws AstartePropertyStorageException {
    List<String> returnedPaths = new ArrayList<>();
    QueryBuilder<AstarteGenericPropertyEntry, String> statementBuilder =
        mPropertyEntryDao.queryBuilder();
    synchronized (this) {
      try {
        statementBuilder
            .where()
            .eq(AstarteGenericPropertyEntry.INTERFACE_FIELD_NAME, astarteInterfaceName);
        List<AstarteGenericPropertyEntry> result =
            mPropertyEntryDao.query(statementBuilder.prepare());
        for (AstarteGenericPropertyEntry entry : result) {
          returnedPaths.add(entry.getPath());
        }
      } catch (SQLException e) {
        throw new AstartePropertyStorageException("Failed to retrieve stored paths", e);
      }
    }

    return returnedPaths;
  }

  @Override
  public Object getStoredValue(AstarteInterface astarteInterface, String path)
      throws AstartePropertyStorageException {
    QueryBuilder<AstarteGenericPropertyEntry, String> statementBuilder =
        mPropertyEntryDao.queryBuilder();
    synchronized (this) {
      try {
        statementBuilder
            .where()
            .eq(
                AstarteGenericPropertyEntry.INTERFACE_FIELD_NAME,
                astarteInterface.getInterfaceName())
            .and()
            .like("path", "%" + path);
        List<AstarteGenericPropertyEntry> result =
            mPropertyEntryDao.query(statementBuilder.prepare());
        if (result.size() == 1) {
          return deserialize(result.get(0).getBSONValue(), mBSONCallback, mBSONDecoder);
        }
        return null;
      } catch (SQLException e) {
        throw new AstartePropertyStorageException("Failed to retrieve stored values", e);
      }
    }
  }

  @Override
  public Map<String, Object> getStoredValuesForInterface(AstarteInterface astarteInterface)
      throws AstartePropertyStorageException {
    Map<String, Object> returnedValues = new HashMap<>();
    QueryBuilder<AstarteGenericPropertyEntry, String> statementBuilder =
        mPropertyEntryDao.queryBuilder();
    synchronized (this) {
      try {
        statementBuilder
            .where()
            .eq(
                AstarteGenericPropertyEntry.INTERFACE_FIELD_NAME,
                astarteInterface.getInterfaceName());
        List<AstarteGenericPropertyEntry> result =
            mPropertyEntryDao.query(statementBuilder.prepare());
        for (AstarteGenericPropertyEntry entry : result) {
          Object value = deserialize(entry.getBSONValue(), mBSONCallback, mBSONDecoder);
          returnedValues.put(entry.getPath(), value);
        }
      } catch (SQLException e) {
        throw new AstartePropertyStorageException("Failed to retrieve stored values", e);
      }
    }

    return returnedValues;
  }

  protected static Object deserialize(
      byte[] bsonValue, BSONCallback bsonCallback, BSONDecoder decoder) {
    // Parse the BSON payload
    bsonCallback.reset();
    decoder.decode(bsonValue, bsonCallback);
    BSONObject decodedPayload = (BSONObject) bsonCallback.get();

    return decodedPayload.get("v");
  }

  @Override
  public void setStoredValue(String interfaceName, String path, Object value)
      throws AstartePropertyStorageException {
    byte[] bsonValue = serialize(value);
    AstarteGenericPropertyEntry entry =
        new AstarteGenericPropertyEntry(interfaceName, path, bsonValue);
    synchronized (this) {
      try {
        mPropertyEntryDao.createOrUpdate(entry);
      } catch (SQLException e) {
        throw new AstartePropertyStorageException("Failed to save stored value", e);
      }
    }
  }

  protected static byte[] serialize(Object value) {
    HashMap<String, Object> bsonJavaObject = new HashMap<>();

    if (value instanceof DateTime) {
      // Special case for DateTime
      bsonJavaObject.put("v", ((DateTime) value).toDate());
    } else {
      bsonJavaObject.put("v", value);
    }
    Document bsonDocument = new Document(bsonJavaObject);

    BasicOutputBuffer out = new BasicOutputBuffer();
    byte[] documentAsByteArray = null;

    try (BsonBinaryWriter w = new BsonBinaryWriter(out)) {
      new DocumentCodec().encode(w, bsonDocument, EncoderContext.builder().build());
      documentAsByteArray = out.toByteArray();
    }

    return documentAsByteArray;
  }

  @Override
  public void removeStoredPath(String interfaceName, String path)
      throws AstartePropertyStorageException {
    synchronized (this) {
      try {
        mPropertyEntryDao.deleteById(interfaceName + "/" + path);
      } catch (SQLException e) {
        throw new AstartePropertyStorageException("Failed to delete stored value", e);
      }
    }
  }

  @Override
  public void purgeProperties(Map<String, List<String>> availableProperties)
      throws AstartePropertyStorageException {
    synchronized (this) {
      // When we get this, we want to clear all keys which aren't part of the received list.
      for (Map.Entry<String, List<String>> entry : availableProperties.entrySet()) {
        for (String storedPath : getStoredPathsForInterface(entry.getKey())) {
          if (!entry.getValue().contains(storedPath)) {
            // Purge!
            removeStoredPath(entry.getKey(), storedPath);
          }
        }
      }
    }
  }
}
