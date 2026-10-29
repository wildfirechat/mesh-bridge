package cn.wildfirechat.bridge.shiro;

import cn.wildfirechat.bridge.utilis.AdminResult;
import com.google.gson.Gson;
import org.apache.shiro.web.filter.authz.PermissionsAuthorizationFilter;
import org.apache.shiro.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class WfPermissionsAuthorizationFilter extends PermissionsAuthorizationFilter {
    private static final Logger LOG = LoggerFactory.getLogger(WfPermissionsAuthorizationFilter.class);
    @Override
    public boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) throws IOException {
        if (WebUtils.toHttp(request).getMethod().equalsIgnoreCase("OPTIONS")){
            return true;
        }
        return super.isAccessAllowed(request, response, mappedValue);
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws IOException {
        PrintWriter out = null;
        try {
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=utf-8");
            out = response.getWriter();

            out.write(new Gson().toJson(AdminResult.error(AdminResult.AdminCode.ERROR_NO_RIGHT)));
        } catch (IOException e) {
            LOG.warn("write permission denied response failed", e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
        return Boolean.FALSE ;
    }
}
