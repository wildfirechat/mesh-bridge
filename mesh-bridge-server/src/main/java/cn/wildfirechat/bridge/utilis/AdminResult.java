package cn.wildfirechat.bridge.utilis;

public class AdminResult {
    public enum AdminCode {
        SUCCESS(0, "success"),
        ERROR_NOT_LOGIN(1, "没有登录"),
        ERROR_NO_RIGHT(2, "没有密码"),
        ERROR_PASSWORD_INCORRECT(3, "密码错误"),
        ERROR_SERVER_ERROR(4, "服务器错误"),
        ERROR_FAILURE_TOO_MUCH_TIMES(5, "密码错误次数太多，请等5分钟再试试"),
        ERROR_NOT_EXIST(6, "对象不存在"),
        ERROR_ALREADY_EXIST(7, "对象已经存在"),
        ERROR_MISS_PARAMETER(8, "却少必要参赛"),
        ;
        public final int code;
        public final String msg;

        AdminCode(int code, String msg) {
            this.code = code;
            this.msg = msg;
        }

    }
    private int code;
    private String message;
    private Object result;

    public static AdminResult ok() {
        return new AdminResult(AdminCode.SUCCESS, null);
    }

    public static AdminResult ok(Object object) {
        return new AdminResult(AdminCode.SUCCESS, object);
    }

    public static AdminResult error(AdminCode code) {
        return new AdminResult(code, null);
    }

    public static AdminResult result(AdminCode code, Object object){
        return new AdminResult(code, object);
    }

    public static AdminResult result(int code, String message, Object object){
        AdminResult r = new AdminResult(AdminCode.SUCCESS, object);
        r.code = code;
        r.message = message;
        return r;
    }

    private AdminResult(AdminCode code, Object result) {
        this.code = code.code;
        this.message = code.msg;
        this.result = result;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }
}
