package cn.wildfirechat.bridge.utilis;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

//限制输入流的最大读取字节数，超过 limit 时抛出 IOException，用于防止超大文件打满带宽/磁盘。
//limit <= 0 表示不限制。
public class LimitedInputStream extends FilterInputStream {

    private final long limit;

    private long count;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if(b != -1) {
            check(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if(n > 0) {
            check(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if(skipped > 0) {
            check(skipped);
        }
        return skipped;
    }

    private void check(long n) throws IOException {
        count += n;
        if(limit > 0 && count > limit) {
            throw new IOException("stream size exceeds the limit of " + limit + " bytes");
        }
    }
}
