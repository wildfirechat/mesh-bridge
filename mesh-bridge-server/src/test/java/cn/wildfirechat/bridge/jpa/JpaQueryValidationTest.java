package cn.wildfirechat.bridge.jpa;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JpaQueryValidationTest {

    private static SessionFactory sessionFactory;

    @BeforeAll
    static void setUp() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.connection.driver_class", "org.h2.Driver");
        settings.put("hibernate.connection.url", "jdbc:h2:mem:bridge;MODE=MySQL;DB_CLOSE_DELAY=-1");
        settings.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        settings.put("hibernate.hbm2ddl.auto", "create-drop");
        settings.put("hibernate.show_sql", "false");
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder().applySettings(settings).build();
        MetadataSources sources = new MetadataSources(registry);
        sources.addAnnotatedClass(OutMessageIds.class);
        sources.addAnnotatedClass(InMessageIds.class);
        sources.addAnnotatedClass(MediaUrlMapping.class);
        sessionFactory = sources.buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    void batchQueriesAreValid() {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            OutMessageIds out = new OutMessageIds();
            out.messageId = 100L;
            out.toDomainId = "B";
            out.toMessageId = 900L;
            session.save(out);

            InMessageIds in = new InMessageIds();
            in.id = new InMessageIdsKey(900L, "A");
            in.localMessageId = 100L;
            session.save(in);

            MediaUrlMapping mapping = new MediaUrlMapping();
            mapping.domainId = "B";
            mapping.localUrlHash = "h1";
            mapping.localMediaUrl = "https://a/x.png";
            mapping.remoteUrlHash = "h2";
            mapping.remoteMediaUrl = "https://b/x.png";
            mapping.createdAt = System.currentTimeMillis();
            session.save(mapping);
            session.flush();

            assertEquals(1, session.createQuery(
                    "select o from OutMessageIds o where o.toDomainId = :domainId and o.messageId in :ids")
                .setParameter("domainId", "B")
                .setParameterList("ids", Arrays.asList(100L, 101L))
                .list().size());

            assertEquals(1, session.createQuery(
                    "select o from OutMessageIds o where o.toDomainId = :domainId and o.toMessageId in :ids")
                .setParameter("domainId", "B")
                .setParameterList("ids", Collections.singletonList(900L))
                .list().size());

            assertEquals(1, session.createQuery(
                    "select i from InMessageIds i where i.id.domainId = :domainId and i.localMessageId in :ids")
                .setParameter("domainId", "A")
                .setParameterList("ids", Collections.singletonList(100L))
                .list().size());
            session.getTransaction().commit();
        }
    }

    @Test
    void mediaMappingCleanupQueriesAreValid() {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            for (int i = 0; i < 3; i++) {
                MediaUrlMapping mapping = new MediaUrlMapping();
                mapping.domainId = "C";
                mapping.localUrlHash = "cl" + i;
                mapping.localMediaUrl = "https://a/c" + i + ".png";
                mapping.remoteUrlHash = "cr" + i;
                mapping.remoteMediaUrl = "https://c/c" + i + ".png";
                mapping.createdAt = 1000L + i;
                session.save(mapping);
            }
            session.flush();

            //和 MediaUrlMappingRepository 里的 JPQL 保持一致：分页取最旧的一批 ID，再按 ID 删除
            java.util.List<Long> ids = session.createQuery(
                    "select m.id from MediaUrlMapping m where m.createdAt is not null and m.createdAt < :cutoff order by m.createdAt", Long.class)
                .setParameter("cutoff", 1002L)
                .setMaxResults(1)
                .list();
            assertEquals(1, ids.size());

            int deleted = session.createQuery("delete from MediaUrlMapping m where m.id in :ids")
                .setParameterList("ids", ids)
                .executeUpdate();
            assertEquals(1, deleted);
            session.getTransaction().rollback();
        }
    }

    @Test
    void mediaMappingTableHasCreatedAtIndex() {
        try (Session session = sessionFactory.openSession()) {
            //H2 的 information_schema 校验 created_at 索引存在，避免清理任务走全表扫描
            Number count = (Number) session.createNativeQuery(
                    "select count(*) from information_schema.indexes where table_name = 'MEDIA_URL_MAPPINGS' and index_name = 'IDX_MEDIA_CREATED_AT'")
                .uniqueResult();
            assertEquals(1, count.intValue());
        }
    }
}
