package cn.wildfirechat.bridge.shiro;

import cn.wildfirechat.bridge.jpa.ShiroSession;
import cn.wildfirechat.bridge.jpa.ShiroSessionRepository;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.support.DefaultSubjectContext;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class DBSessionDao implements SessionDAO {
    private static final Logger LOG = LoggerFactory.getLogger(DBSessionDao.class);
    @Autowired
    private ShiroSessionRepository shiroSessionRepository;

    @Override
    public Serializable create(Session session) {
        String sessionId = UUID.randomUUID().toString().replaceAll("-", "");
        ((SimpleSession) session).setId(sessionId);
        return sessionId;
    }

    @Override
    public Session readSession(Serializable sessionId) throws UnknownSessionException {
        if (!(sessionId instanceof String)) {
            return null;
        }
        ShiroSession shiroSession = shiroSessionRepository.findById((String) sessionId).orElse(null);
        if (shiroSession == null) {
            return null;
        }
        try {
            return byteToSession(shiroSession.getSessionData());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void update(Session session) throws UnknownSessionException {
        byte[] bb = sessionToByte(session);
        if (bb == null) {
            return;
        }
        shiroSessionRepository.save(new ShiroSession((String) session.getId(), bb));
    }

    @Override
    public void delete(Session session) {
        if (session == null || session.getId() == null) {
            return;
        }
        try {
            shiroSessionRepository.deleteById((String) session.getId());
        } catch (Exception e) {
            LOG.warn("delete shiro session {} failed", session.getId());
        }
    }

    /**
     * 修改密码/强制下线时删除该账号的所有会话。
     */
    public void deleteByPrincipal(String account) {
        if (account == null) {
            return;
        }
        List<ShiroSession> sessions = new ArrayList<>();
        shiroSessionRepository.findAll().forEach(sessions::add);
        for (ShiroSession shiroSession : sessions) {
            try {
                Session session = byteToSession(shiroSession.getSessionData());
                Object principals = session.getAttribute(DefaultSubjectContext.PRINCIPALS_SESSION_KEY);
                if (principals instanceof PrincipalCollection
                    && account.equals(((PrincipalCollection) principals).getPrimaryPrincipal())) {
                    shiroSessionRepository.deleteById(shiroSession.getSessionId());
                }
            } catch (Exception ignored) {
                // 无法解析的脏数据在后续读取时也会被拒绝
            }
        }
    }

    @Override
    public Collection<Session> getActiveSessions() {
        List<Session> sessions = new ArrayList<>();
        for (ShiroSession shiroSession : shiroSessionRepository.findAll()) {
            try {
                Session session = byteToSession(shiroSession.getSessionData());
                if (session != null) {
                    sessions.add(session);
                }
            } catch (Exception ignored) {
                // 忽略无法反序列化的历史数据
            }
        }
        return sessions;
    }

    private byte[] sessionToByte(Session session) {
        try (ByteArrayOutputStream bo = new ByteArrayOutputStream();
             ObjectOutputStream oo = new ObjectOutputStream(bo)) {
            oo.writeObject(session);
            oo.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            LOG.error("serialize shiro session failed", e);
            return null;
        }
    }

    private Session byteToSession(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bi = new ByteArrayInputStream(bytes);
             SafeObjectInputStream in = new SafeObjectInputStream(bi)) {
            return (SimpleSession) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("invalid session data", e);
        }
    }

    /**
     * 只允许 Shiro 会话需要的类，阻断通过篡改数据库 session_data 注入 gadget 的路径。
     */
    private static class SafeObjectInputStream extends ObjectInputStream {
        SafeObjectInputStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            String name = desc.getName();
            if (name.startsWith("org.apache.shiro.")
                || name.startsWith("java.lang.")
                || name.startsWith("java.util.")
                || name.startsWith("java.io.")
                || name.startsWith("java.time.")) {
                return super.resolveClass(desc);
            }
            throw new InvalidClassException(name, "class is not allowed in session data");
        }
    }
}
