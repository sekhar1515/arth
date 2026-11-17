import type { Metadata, Viewport } from "next";
import "./globals.css";
import { Providers } from "@/components/providers";
import { themeBootScript } from "@/lib/theme";
import { ServiceWorkerRegister } from "@/components/layout/sw-register";

export const metadata: Metadata = {
  title: { default: "Arth", template: "%s · Arth" },
  description: "Your money, collected, categorised and explained.",
  manifest: "/manifest.webmanifest",
  icons: { icon: "/icons/icon.svg", apple: "/icons/icon-192.png" },
  appleWebApp: { capable: true, title: "Arth", statusBarStyle: "default" },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f5f8f7" },
    { media: "(prefers-color-scheme: dark)", color: "#0b1618" },
  ],
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en-IN" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeBootScript }} />
      </head>
      <body>
        <Providers>{children}</Providers>
        <ServiceWorkerRegister />
      </body>
    </html>
  );
}
