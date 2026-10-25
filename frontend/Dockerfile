# Foundation-level Dockerfile for local development. Runs the Vite dev
# server. A production build/nginx-serve Dockerfile belongs to a later
# hardening/release pass (Account 08), not the Foundation layer.

FROM node:20-alpine
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
