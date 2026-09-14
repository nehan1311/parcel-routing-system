import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Keeps the browser on the Vite origin during local development, so the
// stateless HTTP Basic API can be exercised without changing backend CORS.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
