/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at  http://www.gnu.org/licenses/lgpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jn.sqlhelper.mybatis.spring.datasource;

import com.jn.langx.util.collection.Collects;
import com.jn.langx.util.function.Consumer2;
import com.jn.sqlhelper.datasource.key.DataSourceKey;
import com.jn.sqlhelper.datasource.key.DataSourceKeySelector;
import com.jn.sqlhelper.datasource.key.router.DataSourceKeyRouter;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;


public class DynamicSqlSessionTemplate extends SqlSessionTemplate {
    private DataSourceKeySelector selector;
    private DataSourceKeyRouter mapperDataSourceKeyRouter;

    public DynamicSqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        this(sqlSessionFactory, null);
    }

    public DynamicSqlSessionTemplate(SqlSessionFactory sqlSessionFactory, ExecutorType executorType) {
        super(sqlSessionFactory, ExecutorType.SIMPLE, null);
    }

    public void setSelector(DataSourceKeySelector selector) {
        this.selector = selector;
    }

    public void setMapperDataSourceKeyRouter(DataSourceKeyRouter mapperDataSourceKeyRouter) {
        this.mapperDataSourceKeyRouter = mapperDataSourceKeyRouter;
    }

    private DynamicSqlSessionFactory getDynamicSqlSessionFactory() {
        return (DynamicSqlSessionFactory) this.getSqlSessionFactory();
    }


    /**
     * {@inheritDoc}
     * 在初始化阶段，初始化 各种Mapper时调用该方法
     */
    @Override
    public <T> T getMapper(final Class<T> mapperInterface) {
        DynamicSqlSessionFactory sessionFactory = getDynamicSqlSessionFactory();
        final Map<DataSourceKey, Object> delegateMapperMap = Collects.emptyHashMap();
        Collects.forEach(sessionFactory.getDelegates(), new Consumer2<DataSourceKey, SqlSessionFactory>() {
            @Override
            public void accept(DataSourceKey key, SqlSessionFactory delegateFactory) {
                Object mybatisMapperProxy = delegateFactory.getConfiguration().getMapper(mapperInterface, DynamicSqlSessionTemplate.this);
                delegateMapperMap.put(key, mybatisMapperProxy);
            }
        });
        DynamicMapper mapper = new DynamicMapper(mapperInterface, delegateMapperMap, selector);
        if (mapperDataSourceKeyRouter != null) {
            mapper.setRouter(mapperDataSourceKeyRouter);
        }
        return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[]{mapperInterface}, mapper);
    }

    @Override
    public SqlSessionFactory getSqlSessionFactory() {
        return super.getSqlSessionFactory();
    }

    public SqlSessionFactory getLocalSqlSessionFactory() {
        DynamicSqlSessionFactory sessionFactory = getDynamicSqlSessionFactory();
        if (sessionFactory.size() == 1) {
            return sessionFactory;
        }
        // XXXXXXXXXXXXXXXXXXXXX
        return getSqlSessionFactory();
    }

    /**
     * {@inheritDoc}
     * 如果只有一个数据源，则直接就是原始Configuration
     * 如果有多个，并且未指定取哪个，则取 primary 数据源的
     * 如果有多个，并且指定取了哪个，则取 指定的数据源的
     */
    @Override
    public Configuration getConfiguration() {
        DynamicSqlSessionFactory sessionFactory = getDynamicSqlSessionFactory();
        if (sessionFactory.size() == 1) {
            return sessionFactory.getConfiguration();
        }
        return this.getLocalSqlSessionFactory().getConfiguration();
    }


    /**
     * Proxy needed to route MyBatis method calls to the proper SqlSession got
     * from Spring's Transaction Manager
     * It also unwraps exceptions thrown by {@code Method#invoke(Object, Object...)} to
     * pass a {@code PersistenceException} to the {@code PersistenceExceptionTranslator}.
     */
    private class MultiDataSourceSqlSessionInterceptor implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            SqlSession sqlSession = SqlSessionUtils.getSqlSession(
                    DynamicSqlSessionTemplate.this.getSqlSessionFactory(),
                    DynamicSqlSessionTemplate.this.getExecutorType(),
                    DynamicSqlSessionTemplate.this.getPersistenceExceptionTranslator());
            try {
                Object result = method.invoke(sqlSession, args);
                if (!SqlSessionUtils.isSqlSessionTransactional(sqlSession, DynamicSqlSessionTemplate.this.getSqlSessionFactory())) {
                    // force commit even on non-dirty sessions because some databases require
                    // a commit/rollback before calling close()
                    sqlSession.commit(true);
                }
                return result;
            } catch (Throwable t) {
                Throwable unwrapped = ExceptionUtil.unwrapThrowable(t);
                if (DynamicSqlSessionTemplate.this.getPersistenceExceptionTranslator() != null && unwrapped instanceof PersistenceException) {
                    // release the connection to avoid a deadlock if the translator is no loaded. See issue #22
                    SqlSessionUtils.closeSqlSession(sqlSession, DynamicSqlSessionTemplate.this.getSqlSessionFactory());
                    sqlSession = null;
                    Throwable translated = DynamicSqlSessionTemplate.this.getPersistenceExceptionTranslator().translateExceptionIfPossible((PersistenceException) unwrapped);
                    if (translated != null) {
                        unwrapped = translated;
                    }
                }
                throw unwrapped;
            } finally {
                if (sqlSession != null) {
                    SqlSessionUtils.closeSqlSession(sqlSession, DynamicSqlSessionTemplate.this.getSqlSessionFactory());
                }
            }
        }
    }

}
