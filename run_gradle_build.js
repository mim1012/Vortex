const { spawn } = require('child_process');
const fs = require('fs');

console.log('Starting Gradle release build...');

const gradleProcess = spawn('cmd.exe', [
  '/c',
  '.\\gradlew.bat clean assembleRelease --console=plain'
], {
  cwd: 'D:\\Project\\TwinMe_New_Project',
  shell: true,
  stdio: ['inherit', 'pipe', 'pipe'],
  env: {
    ...process.env,
    JAVA_HOME: 'D:\\Android\\Android Studio\\jbr'
  }
});

let output = '';

gradleProcess.stdout.on('data', (data) => {
  const text = data.toString();
  output += text;
  process.stdout.write(text);
});

gradleProcess.stderr.on('data', (data) => {
  const text = data.toString();
  output += text;
  process.stderr.write(text);
});

gradleProcess.on('close', (code) => {
  console.log(`\n\nBuild process exited with code ${code}`);

  // Save output to file
  fs.writeFileSync('D:\\Project\\TwinMe_New_Project\\gradle_build_output.txt', output);
  console.log('Build output saved to gradle_build_output.txt');

  if (code === 0) {
    console.log('\n✓ BUILD SUCCESSFUL');
    console.log('APK location: D:\\Project\\TwinMe_New_Project\\app\\build\\outputs\\apk\\release\\app-release.apk');
  } else {
    console.log('\n✗ BUILD FAILED');
  }

  process.exit(code);
});
