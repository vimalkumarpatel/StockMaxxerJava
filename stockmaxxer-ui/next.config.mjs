// Simplification: the browser only talks to Next.js; /api/* is proxied to the Spring Boot app, so no CORS
// setup is needed. SM_API_URL is read when the server starts (next dev) or when it is built (next build).
const api = process.env.SM_API_URL ?? 'http://localhost:8080';

export default {
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${api}/api/:path*` }];
  },
};
