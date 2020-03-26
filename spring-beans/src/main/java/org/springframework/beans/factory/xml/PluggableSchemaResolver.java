/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.xml;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * {@link EntityResolver} implementation that attempts to resolve schema URLs into
 * local {@link ClassPathResource classpath resources} using a set of mappings files.
 *
 * <p>By default, this class will look for mapping files in the classpath using the
 * pattern: {@code META-INF/spring.schemas} allowing for multiple files to exist on
 * the classpath at any one time.
 *
 * <p>The format of {@code META-INF/spring.schemas} is a properties file where each line
 * should be of the form {@code systemId=schema-location} where {@code schema-location}
 * should also be a schema file in the classpath. Since {@code systemId} is commonly a
 * URL, one must be careful to escape any ':' characters which are treated as delimiters
 * in properties files.
 *
 * <p>The pattern for the mapping files can be overridden using the
 * {@link #PluggableSchemaResolver(ClassLoader, String)} constructor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 */
public class PluggableSchemaResolver implements EntityResolver {

	/**
	 * The location of the file that defines schema mappings.
	 * Can be present in multiple JAR files.
	 */
	public static final String DEFAULT_SCHEMA_MAPPINGS_LOCATION = "META-INF/spring.schemas";


	private static final Log logger = LogFactory.getLog(PluggableSchemaResolver.class);

	@Nullable
	private final ClassLoader classLoader;

	private final String schemaMappingsLocation;

	/** Stores the mapping of schema URL -> local schema path. */
	@Nullable
	private volatile Map<String, String> schemaMappings;


	/**
	 * Loads the schema URL -> schema file location mappings using the default
	 * mapping file pattern "META-INF/spring.schemas".
	 * @param classLoader the ClassLoader to use for loading
	 * (can be {@code null}) to use the default ClassLoader)
	 * @see PropertiesLoaderUtils#loadAllProperties(String, ClassLoader)
	 */
	public PluggableSchemaResolver(@Nullable ClassLoader classLoader) {
		this.classLoader = classLoader;
		this.schemaMappingsLocation = DEFAULT_SCHEMA_MAPPINGS_LOCATION;
	}

	/**
	 * Loads the schema URL -> schema file location mappings using the given
	 * mapping file pattern.
	 * @param classLoader the ClassLoader to use for loading
	 * (can be {@code null}) to use the default ClassLoader)
	 * @param schemaMappingsLocation the location of the file that defines schema mappings
	 * (must not be empty)
	 * @see PropertiesLoaderUtils#loadAllProperties(String, ClassLoader)
	 */
	public PluggableSchemaResolver(@Nullable ClassLoader classLoader, String schemaMappingsLocation) {
		Assert.hasText(schemaMappingsLocation, "'schemaMappingsLocation' must not be empty");
		this.classLoader = classLoader;
		this.schemaMappingsLocation = schemaMappingsLocation;
	}


	@Override
	@Nullable
	public InputSource resolveEntity(@Nullable String publicId, @Nullable String systemId) throws IOException {
		if (logger.isTraceEnabled()) {
			logger.trace("Trying to resolve XML entity with public id [" + publicId +
					"] and system id [" + systemId + "]");
		}

		// systemId 例子 ：http://www.springframework.org/schema/beans/spring-beans.xsd

		// 如果 systemId 不为空，则执行解析
		if (systemId != null) {
			// 通过 systemId 获取 resourceLocation
			String resourceLocation = getSchemaMappings().get(systemId);
			// 如果没有获取到 resourceLocation 并且 systemId 是以 https: 开头
			if (resourceLocation == null && systemId.startsWith("https:")) {
				// Retrieve canonical http schema mapping even for https declaration

				// 尝试一下使用 http 获取
				resourceLocation = getSchemaMappings().get("http:" + systemId.substring(6));
			}

			// 如果拿到了资源的位置
			if (resourceLocation != null) {
				// 在指定的目录下读取 spring-beans.xsd
				Resource resource = new ClassPathResource(resourceLocation, this.classLoader);
				try {
					// 将 spring-beans.xsd 转化成 SAX 输入源
					InputSource source = new InputSource(resource.getInputStream());
					// 为 SAX 输入源设置 publicId 和 systemId
					source.setPublicId(publicId);
					source.setSystemId(systemId);
					if (logger.isTraceEnabled()) {
						logger.trace("Found XML schema [" + systemId + "] in classpath: " + resourceLocation);
					}
					// 返回 SAX 输入源
					return source;
				}
				catch (FileNotFoundException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Could not find XML schema [" + systemId + "]: " + resource, ex);
					}
				}
			}
		}

		// Fall back to the parser's default behavior.
		return null;
	}

	/**
	 * Load the specified schema mappings lazily.
	 *
	 * 懒加载 schema 映射
	 */
	private Map<String, String> getSchemaMappings() {
		// 先获取 schema 映射
		Map<String, String> schemaMappings = this.schemaMappings;

		// 双重判断加锁，单例模式
		if (schemaMappings == null) {
			synchronized (this) {
				schemaMappings = this.schemaMappings;
				if (schemaMappings == null) {
					if (logger.isTraceEnabled()) {
						logger.trace("Loading schema mappings from [" + this.schemaMappingsLocation + "]");
					}
					try {
						// 通过 schemaMappingsLocation 获取所有的 schema 配置
						// schemaMappingsLocation 默认为 "META-INF/spring.schemas"
						Properties mappings =
								PropertiesLoaderUtils.loadAllProperties(this.schemaMappingsLocation, this.classLoader);
						if (logger.isTraceEnabled()) {
							logger.trace("Loaded schema mappings: " + mappings);
						}
						// 新建 ConcurrentHashMap
						schemaMappings = new ConcurrentHashMap<>(mappings.size());
						// 将获取到的 schema 配置复制到 Map 中
						CollectionUtils.mergePropertiesIntoMap(mappings, schemaMappings);
						this.schemaMappings = schemaMappings;
					}
					catch (IOException ex) {
						throw new IllegalStateException(
								"Unable to load schema mappings from location [" + this.schemaMappingsLocation + "]", ex);
					}
				}
			}
		}
		return schemaMappings;
	}


	@Override
	public String toString() {
		return "EntityResolver using schema mappings " + getSchemaMappings();
	}

}
