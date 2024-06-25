package cn.wildfirechat.bridge.shiro;

import cn.wildfirechat.bridge.utilis.AdminResult;
import com.google.gson.Gson;
import org.apache.shiro.web.filter.AccessControlFilter;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.servlet.ShiroHttpServletRequest;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.PrintWriter;

public class JsonAuthLoginFilter extends AccessControlFilter {

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue)
            throws Exception {
        if (request instanceof HttpServletRequest) {
            if (((HttpServletRequest) request).getMethod().toUpperCase().equals("OPTIONS")) {
                return true;
            }
        }

        Subject subject = SecurityUtils.getSubject();

        if(null != subject){
            if(subject.isRemembered()){
                return Boolean.TRUE;
            }
            if(subject.isAuthenticated()){
                return Boolean.TRUE;
            }
        }

        return Boolean.FALSE ;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        PrintWriter out = null;
        try {
            if(!((ShiroHttpServletRequest) request).getRequestURI().startsWith("/admin/index.html") && !((ShiroHttpServletRequest) request).getRequestURI().startsWith("/admin/assets")) {
                response.setCharacterEncoding("UTF-8");
                response.setContentType("application/json; charset=utf-8");
            }
            out = response.getWriter();

            out.write(new Gson().toJson(AdminResult.error(AdminResult.AdminCode.ERROR_NOT_LOGIN)));
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
        return Boolean.FALSE ;
    }
}