package br.com.gestorcobranca.publicapi.web.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contagem dos bytes do corpo efetivamente trafegado: lidos da requisição e escritos na resposta pela aplicação. A
 * compressão HTTP ({@code server.compression}) é aplicada depois, pelo conector — o consumo medido é o corpo sem
 * compressão, independente da negociação de {@code Accept-Encoding} do cliente.
 */
final class CountingStreams {

    private CountingStreams() {
    }

    static final class Request extends HttpServletRequestWrapper {

        private final AtomicLong count = new AtomicLong();
        private ServletInputStream stream;
        private BufferedReader reader;

        Request(HttpServletRequest request) {
            super(request);
        }

        long bytesRead() {
            return count.get();
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream delegate = super.getInputStream();
                stream = new ServletInputStream() {
                    @Override
                    public int read() throws IOException {
                        int b = delegate.read();
                        if (b >= 0) {
                            count.incrementAndGet();
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        int n = delegate.read(buf, off, len);
                        if (n > 0) {
                            count.addAndGet(n);
                        }
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return delegate.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        delegate.setReadListener(listener);
                    }
                };
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    static final class Response extends HttpServletResponseWrapper {

        private final AtomicLong count = new AtomicLong();
        private ServletOutputStream stream;
        private PrintWriter writer;

        Response(HttpServletResponse response) {
            super(response);
        }

        long bytesWritten() {
            if (writer != null) {
                writer.flush();
            }
            return count.get();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream delegate = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        delegate.write(b);
                        count.incrementAndGet();
                    }

                    @Override
                    public void write(byte[] buf, int off, int len) throws IOException {
                        delegate.write(buf, off, len);
                        count.addAndGet(len);
                    }

                    @Override
                    public void flush() throws IOException {
                        delegate.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        delegate.close();
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        delegate.setWriteListener(listener);
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            super.flushBuffer();
        }
    }
}
