/** @type {import('next').NextConfig} */
const apiOrigin = process.env.API_ORIGIN || "http://localhost:8080";

const nextConfig = {
  output: "standalone",
  reactStrictMode: true,
  poweredByHeader: false,
  // Same-origin API: the browser only ever talks to this server, so the session cookie can be SameSite=Strict.
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiOrigin}/api/:path*` }];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "same-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
        ],
      },
    ];
  },
};

export default nextConfig;
