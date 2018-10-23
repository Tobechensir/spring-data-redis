/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands.ByteMapRecord;
import org.springframework.data.redis.connection.RedisStreamCommands.Consumer;
import org.springframework.data.redis.connection.RedisStreamCommands.MapRecord;
import org.springframework.data.redis.connection.RedisStreamCommands.ReadOffset;
import org.springframework.data.redis.connection.RedisStreamCommands.RecordId;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamOffset;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamReadOptions;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
import org.springframework.data.redis.hash.HashMapper;
import org.springframework.data.redis.hash.ObjectHashMapper;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Default implementation of {@link ListOperations}.
 *
 * @author Mark Paluch
 * @since 2.2
 */
class DefaultStreamOperations<K, HK, HV> extends AbstractOperations<K, Object> implements StreamOperations<K, HK, HV> {

	private final RedisCustomConversions rcc = new RedisCustomConversions();
	private DefaultConversionService conversionService;
	private HashMapper<?, HK, HV> mapper;

	DefaultStreamOperations(RedisTemplate<K, ?> template) {
		super((RedisTemplate<K, Object>) template);

		this.conversionService = new DefaultConversionService();
		this.mapper = mapper != null ? mapper : (HashMapper<?, HK, HV>) new ObjectHashMapper();
		rcc.registerConvertersIn(conversionService);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#acknowledge(java.lang.Object, java.lang.String, java.lang.String[])
	 */
	@Override
	public Long acknowledge(K key, String group, String... messageIds) {

		byte[] rawKey = rawKey(key);
		return execute(connection -> connection.xAck(rawKey, group, messageIds), true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#add(java.lang.Object, java.util.Map)
	 */
	@Override
	public RecordId add(MapRecord<K, HK, HV> record) {

		ByteMapRecord binaryRecord = record.serialize(keySerializer(), hashKeySerializer(), hashValueSerializer());

		return execute(connection -> connection.xAdd(binaryRecord), true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#delete(java.lang.Object, java.lang.String[])
	 */
	@Override
	public Long delete(K key, String... messageIds) {

		byte[] rawKey = rawKey(key);
		return execute(connection -> connection.xDel(rawKey, messageIds), true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#createGroup(java.lang.Object, org.springframework.data.redis.connection.RedisStreamCommands.ReadOffset, java.lang.String)
	 */
	@Override
	public String createGroup(K key, ReadOffset readOffset, String group) {

		byte[] rawKey = rawKey(key);
		return execute(connection -> connection.xGroupCreate(rawKey, group, readOffset), true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#deleteConsumer(java.lang.Object, org.springframework.data.redis.connection.RedisStreamCommands.Consumer)
	 */
	@Override
	public Boolean deleteConsumer(K key, Consumer consumer) {

		byte[] rawKey = rawKey(key);
		return execute(connection -> connection.xGroupDelConsumer(rawKey, consumer), true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#destroyGroup(java.lang.Object, java.lang.String)
	 */
	@Override
	public Boolean destroyGroup(K key, String group) {

		byte[] rawKey = rawKey(key);
		return execute(connection -> connection.xGroupDestroy(rawKey, group), true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#size(java.lang.Object)
	 */
	@Override
	public Long size(K key) {

		byte[] rawKey = rawKey(key);
		return execute(connection -> connection.xLen(rawKey), true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#range(java.lang.Object, org.springframework.data.domain.Range, org.springframework.data.redis.connection.RedisZSetCommands.Limit)
	 */
	@Override
	public List<MapRecord<K, HK, HV>> range(K key, Range<String> range, Limit limit) {

		return execute(new RecordDeserializingRedisCallback<K, HK, HV>() {

			@Nullable
			@Override
			List<ByteMapRecord> inRedis(RedisConnection connection) {
				return connection.xRange(rawKey(key), range, limit);
			}
		}, true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#read(org.springframework.data.redis.connection.RedisStreamCommands.StreamReadOptions, org.springframework.data.redis.connection.RedisStreamCommands.StreamOffset[])
	 */
	@Override
	public List<MapRecord<K, HK, HV>> read(StreamReadOptions readOptions, StreamOffset<K>... streams) {

		return execute(new RecordDeserializingRedisCallback<K, HK, HV>() {

			@Nullable
			@Override
			List<ByteMapRecord> inRedis(RedisConnection connection) {
				return connection.xRead(readOptions, rawStreamOffsets(streams));
			}
		}, true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#read(org.springframework.data.redis.connection.RedisStreamCommands.Consumer, org.springframework.data.redis.connection.RedisStreamCommands.StreamReadOptions, org.springframework.data.redis.connection.RedisStreamCommands.StreamOffset[])
	 */
	@Override
	public List<MapRecord<K, HK, HV>> read(Consumer consumer, StreamReadOptions readOptions, StreamOffset<K>... streams) {

		return execute(new RecordDeserializingRedisCallback<K, HK, HV>() {

			@Nullable
			@Override
			List<ByteMapRecord> inRedis(RedisConnection connection) {
				return connection.xReadGroup(consumer, readOptions, rawStreamOffsets(streams));
			}
		}, true);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.core.StreamOperations#reverseRange(java.lang.Object, org.springframework.data.domain.Range, org.springframework.data.redis.connection.RedisZSetCommands.Limit)
	 */
	@Override
	public List<MapRecord<K, HK, HV>> reverseRange(K key, Range<String> range, Limit limit) {

		return execute(new RecordDeserializingRedisCallback<K, HK, HV>() {

			@Nullable
			@Override
			List<ByteMapRecord> inRedis(RedisConnection connection) {
				return connection.xRevRange(rawKey(key), range, limit);
			}
		}, true);
	}

	@Override
	public Long trim(K key, long count) {

		byte[] rawKey = rawKey(key);
		return execute(connection -> connection.xTrim(rawKey, count), true);
	}

	@Override
	public <V> HashMapper<V, HK, HV> getHashMapper(Class<V> targetType) {

		if (rcc.isSimpleType(targetType)) {

			return new HashMapper<V, HK, HV>() {

				@Override
				public Map<HK, HV> toHash(V object) {

					HK key = (HK) "payload";
					HV value = (HV) object;

					if (!template.isEnableDefaultSerializer()) {
						if (template.getHashKeySerializer() == null) {
							key = (HK) key.toString().getBytes(StandardCharsets.UTF_8);
						}
						if (template.getHashValueSerializer() == null) {
							value = (HV) serializeHashValueIfRequires((HV) object);
						}
					}

					return Collections.singletonMap(key, value);

					// return (Map<HK, HV>) Collections.singletonMap("payload".getBytes(StandardCharsets.UTF_8),
					// serializeHashValueIfRequires((HV) object));
				}

				@Override
				public V fromHash(Map<HK, HV> hash) {
					Object value = hash.values().iterator().next();
					if (ClassUtils.isAssignableValue(targetType, value)) {
						return (V) value;
					}
					return (V) deserializeHashValue((byte[]) value, (Class<HV>) targetType);
				}
			};
		}

		if (mapper instanceof ObjectHashMapper) {

			return new HashMapper<V, HK, HV>() {

				@Override
				public Map<HK, HV> toHash(V object) {
					return (Map<HK, HV>) ((ObjectHashMapper) mapper).toObjectHash(object);
				}

				@Override
				public V fromHash(Map<HK, HV> hash) {

					Map<byte[], byte[]> map = hash.entrySet().stream()
							.collect(Collectors.toMap(e -> conversionService.convert((Object) e.getKey(), byte[].class),
									e -> conversionService.convert((Object) e.getValue(), byte[].class)));

					return (V) mapper.fromHash((Map<HK, HV>) map);
				}
			};

		}

		return (HashMapper<V, HK, HV>) mapper;
	}

	protected byte[] serializeHashKeyIfRequired(HK key) {

		return hashKeySerializerPresent() ? serialize(key, hashKeySerializer())
				: conversionService.convert(key, byte[].class);
	}

	protected boolean hashKeySerializerPresent() {
		return hashValueSerializer() != null;
	}

	protected byte[] serializeHashValueIfRequires(HV value) {
		return hashValueSerializerPresent() ? serialize(value, hashValueSerializer())
				: conversionService.convert(value, byte[].class);
	}

	protected boolean hashValueSerializerPresent() {
		return hashValueSerializer() != null;
	}

	protected byte[] serializeKeyIfRequired(K key) {
		return keySerializerPresent() ? serialize(key, keySerializer()) : conversionService.convert(key, byte[].class);
	}

	protected boolean keySerializerPresent() {
		return keySerializer() != null;
	}

	protected K deserializeKey(byte[] bytes, Class<K> targetType) {
		return keySerializerPresent() ? (K) keySerializer().deserialize(bytes)
				: conversionService.convert(bytes, targetType);
	}

	protected HK deserializeHashKey(byte[] bytes, Class<HK> targetType) {

		return hashKeySerializerPresent() ? (HK) hashKeySerializer().deserialize(bytes)
				: conversionService.convert(bytes, targetType);
	}

	protected HV deserializeHashValue(byte[] bytes, Class<HV> targetType) {
		return hashValueSerializerPresent() ? (HV) hashValueSerializer().deserialize(bytes)
				: conversionService.convert(bytes, targetType);
	}

	byte[] serialize(Object value, RedisSerializer serializer) {

		Object _value = value;
		if (!serializer.canSerialize(value.getClass())) {
			_value = conversionService.convert(value, serializer.getTargetType());
		}
		return serializer.serialize(_value);
	}

	private Map.Entry<byte[], byte[]> mapToBinary(Map.Entry<? extends HK, ? extends HV> it) {

		return new Map.Entry<byte[], byte[]>() {

			@Override
			public byte[] getKey() {
				return serializeHashKeyIfRequired(it.getKey());
			}

			@Override
			public byte[] getValue() {
				return serializeHashValueIfRequires(it.getValue());
			}

			@Override
			public byte[] setValue(byte[] value) {
				return new byte[0];
			}
		};
	}

	private Map.Entry<HK, HV> mapToObject(Map.Entry<byte[], byte[]> pair) {

		return new Map.Entry<HK, HV>() {

			@Override
			public HK getKey() {
				return deserializeHashKey(pair.getKey(), (Class<HK>) Object.class);
			}

			@Override
			public HV getValue() {
				return deserializeHashValue(pair.getValue(), (Class<HV>) Object.class);
			}

			@Override
			public HV setValue(HV value) {
				return value;
			}

		};
	}

	@SuppressWarnings("unchecked")
	private Map<HK, HV> deserializeBody(@Nullable Map<byte[], byte[]> entries) {
		// connection in pipeline/multi mode

		if (entries == null) {
			return null;
		}

		Map<HK, HV> map = new LinkedHashMap<>(entries.size());

		for (Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
			map.put(deserializeHashKey(entry.getKey()), deserializeHashValue(entry.getValue()));
		}

		return map;
	}

	@SuppressWarnings("unchecked")
	private StreamOffset<byte[]>[] rawStreamOffsets(StreamOffset<K>[] streams) {

		return Arrays.stream(streams) //
				.map(it -> StreamOffset.create(rawKey(it.getKey()), it.getOffset())) //
				.toArray(it -> new StreamOffset[it]);
	}

	abstract class RecordDeserializingRedisCallback<K, HK, HV> implements RedisCallback<List<MapRecord<K, HK, HV>>> {

		public final List<MapRecord<K, HK, HV>> doInRedis(RedisConnection connection) {

			List<ByteMapRecord> x = inRedis(connection);

			List<MapRecord<K, HK, HV>> result = new ArrayList<>();
			for (ByteMapRecord record : x) {
				result.add(record.deserialize(keySerializer(), hashKeySerializer(), hashValueSerializer()));
			}

			return result;
		}

		@Nullable
		abstract List<ByteMapRecord> inRedis(RedisConnection connection);
	}
}
