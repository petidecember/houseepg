# HouseEPG frontend

Optional web interface: media overview and app configuration. The server starts and serves DLNA
without this frontend; the Gradle build never invokes Node.

## Development

```shell
cd frontend
npm ci
npm run dev
```

The Vite dev server runs on <http://localhost:5173> and proxies `/api`, `/media` and `/actuator`
to the Spring app on <http://localhost:8094>.

## Building for the server

```shell
cd frontend
npm ci
npm run build
```

Then copy the built files into the Spring app's static resources:

```shell
# from the repository root
cp -r frontend/dist/* src/main/resources/static/
```

Start the server (`./gradlew bootRun`) and open <http://localhost:8094/>.

The copied files are generated output and are ignored by git; delete them (or run
`git clean -fd src/main/resources/static`) to go back to the API-only server.
