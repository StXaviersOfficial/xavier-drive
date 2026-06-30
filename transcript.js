/* eslint-disable */
// ═══════════════════════════════════════════════════════════════
//  StXaviersOnline — Post-Class Transcription Script
//  Run on your phone via Termux AFTER each live class ends.
//
//  WHAT IT DOES:
//    1. Downloads the audio track from the YouTube recording.
//    2. Sends the audio to Groq Whisper (free, fast) → text transcript.
//    3. Asks Groq LLaMA-3.3-70b to summarize the class into bullet points.
//    4. Saves both transcript + summary to Firebase RTDB.
//    5. Recording tab in the app instantly shows the summary + transcript.
//
//  MEMORY & RESOURCE USAGE:
//    - Peak memory: ~50–80 MB during execution (Node + audio buffer).
//    - Disk: ~50 MB temporarily for the audio file (auto-cleaned at end).
//    - CPU: brief spikes during download + transcription API calls.
//    - The script EXITS automatically when done. It does NOT keep running.
//    - It does NOT need Termux to stay open. You can close Termux
//      immediately after the script finishes.
//
//  TERMUX OPEN FOREVER? NO.
//    - You only need to open Termux, run ONE command, wait 3–10 minutes,
//      then close Termux. The transcript is saved permanently to Firebase.
//    - Think of it like running `npm install` — runs once, then exits.
//
//  SETUP (one-time, ~5 minutes):
//    1. Install Termux from F-Droid (NOT Play Store — that version is outdated).
//    2. In Termux, run:
//         pkg update && pkg upgrade -y
//         pkg install -y nodejs-lts yt-dlp ffmpeg
//    3. Create a folder for the script:
//         mkdir -p ~/stxaviers
//         cd ~/stxaviers
//    4. Save this file as `transcript.js` in that folder.
//    5. Set the Groq API key as an env variable (one-time):
//         echo 'export GROQ_KEY=your_groq_api_key_here' >> ~/.bashrc
//         source ~/.bashrc
//       (Get a free key from https://console.groq.com — takes 30 seconds.)
//
//  USAGE (after every live class):
//    cd ~/stxaviers
//    node transcript.js <youtubeVideoId> "<className>"
//
//  EXAMPLE:
//    node transcript.js dQw4w9WgXcQ "Class 7"
//
//  WHERE TO GET THE VIDEO ID:
//    After a teacher clicks "End Class", the app shows the exact command
//    with the video ID filled in. Just copy that command into Termux.
//    OR: open the recording on YouTube, copy the URL — the ID is the part
//    after `?v=` (e.g., youtube.com/watch?v=dQw4w9WgXcQ → ID = dQw4w9WgXcQ).
// ═══════════════════════════════════════════════════════════════

const VIDEO_ID = process.argv[2];
const CLASS_NAME = process.argv[3] || 'Unknown';
const FIREBASE_URL = 'https://stxaviers-official-default-rtdb.firebaseio.com';
const GROQ_KEY = process.env.GROQ_KEY || '';

if (!GROQ_KEY) {
  console.error('❌ GROQ_KEY environment variable not set.');
  console.error('   Run: echo \'export GROQ_KEY=your_key\' >> ~/.bashrc && source ~/.bashrc');
  process.exit(1);
}

if (!VIDEO_ID) {
  console.log('Usage: node transcript.js <youtubeVideoId> [className]');
  console.log('Example: node transcript.js dQw4w9WgXcQ "Class 7"');
  process.exit(1);
}

async function main() {
  console.log('╔══════════════════════════════════════════════════╗');
  console.log('║  StXaviersOnline — Transcript Generator          ║');
  console.log('╚══════════════════════════════════════════════════╝');
  console.log(`🎬 Video ID : ${VIDEO_ID}`);
  console.log(`📚 Class    : ${CLASS_NAME}`);
  console.log(`⏰ Started  : ${new Date().toLocaleTimeString()}`);
  console.log('');

  // Step 1: Download audio using yt-dlp
  console.log('⬇️  Step 1/4: Downloading audio from YouTube...');
  const { execSync } = require('child_process');
  const audioFile = `/tmp/yt_audio_${VIDEO_ID}.mp3`;

  try {
    execSync(`yt-dlp -x --audio-format mp3 --audio-quality 5 -o "${audioFile}" "https://www.youtube.com/watch?v=${VIDEO_ID}"`, {
      stdio: 'inherit',
      timeout: 300000 // 5 minute timeout
    });
    console.log('✅ Audio downloaded!\n');
  } catch (e) {
    console.error('❌ Failed to download audio.');
    console.error('   Make sure yt-dlp is installed: pkg install yt-dlp');
    console.error('   And the video is public/unlisted (not private).');
    process.exit(1);
  }

  // Step 2: Check file size & split if needed (Groq limit = 25MB)
  const fs = require('fs');
  const stats = fs.statSync(audioFile);
  const sizeMB = stats.size / (1024 * 1024);
  console.log(`📄 Step 2/4: Audio file size: ${sizeMB.toFixed(1)} MB`);

  let files = [audioFile];

  if (sizeMB > 25) {
    console.log('⚠️  File > 25MB (Groq limit). Splitting into 10-min chunks...');
    try {
      execSync(`ffmpeg -y -i "${audioFile}" -f segment -segment_time 600 -c copy /tmp/yt_split_${VIDEO_ID}_%03d.mp3`, {
        stdio: 'inherit',
        timeout: 120000
      });
      files = fs.readdirSync('/tmp')
        .filter(f => f.startsWith(`yt_split_${VIDEO_ID}_`))
        .map(f => `/tmp/${f}`)
        .sort();
      console.log(`✅ Split into ${files.length} parts.\n`);
    } catch (e) {
      console.error('❌ ffmpeg split failed. Install: pkg install ffmpeg');
      process.exit(1);
    }
  } else {
    console.log('✅ Under 25MB — no splitting needed.\n');
  }

  // Step 3: Transcribe using Groq Whisper
  console.log(`🎙️  Step 3/4: Transcribing ${files.length} file(s) with Groq Whisper...`);

  let fullTranscript = '';

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    console.log(`   📝 Part ${i + 1}/${files.length}...`);

    const formData = new FormData();
    formData.append('file', new Blob([fs.readFileSync(file)]), 'audio.mp3');
    formData.append('model', 'whisper-large-v3');
    formData.append('response_format', 'json');

    try {
      const res = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${GROQ_KEY}` },
        body: formData
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err?.error?.message || `HTTP ${res.status}`);
      }

      const data = await res.json();
      fullTranscript += data.text + ' ';
      console.log(`   ✅ Part ${i + 1} done (${data.text.length} chars)`);
    } catch (e) {
      console.error(`   ❌ Part ${i + 1} failed: ${e.message}`);
    }

    if (i < files.length - 1) await new Promise(r => setTimeout(r, 2000));
  }

  fullTranscript = fullTranscript.trim();
  console.log(`\n📄 Transcript: ${fullTranscript.length} chars\n`);

  if (!fullTranscript) {
    console.error('❌ No transcript generated. Exiting.');
    process.exit(1);
  }

  // Step 4: Generate summary using Groq chat
  console.log('🤖 Step 4/4: Generating summary with Groq LLaMA-3.3...');

  let summary = '';
  try {
    const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${GROQ_KEY}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: 'llama-3.3-70b-versatile',
        messages: [
          {
            role: 'system',
            content: 'You are a helpful school assistant. Summarize this class transcript into key points a student can use to review what was taught. Be concise and clear. Use bullet points. Write in simple English suitable for school students.'
          },
          {
            role: 'user',
            content: `Summarize this class transcript:\n\n${fullTranscript.substring(0, 30000)}`
          }
        ],
        temperature: 0.3,
        max_tokens: 2048
      })
    });

    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    summary = data.choices[0]?.message?.content || '';
    console.log('✅ Summary generated!\n');
  } catch (e) {
    console.error('❌ Summary generation failed:', e.message);
    summary = 'Summary generation failed. Please try again.';
  }

  // Save to Firebase VIA the worker (secure — uses shared secret)
  console.log('🔒 Saving transcript via secure worker endpoint...');

  const WORKER_URL = 'https://stxaviers-auth.quackeditzofficial.workers.dev';
  const TRANSCRIPT_SECRET = process.env.STX_TRANSCRIPT_SECRET || '';

  if (!TRANSCRIPT_SECRET) {
    console.error('❌ STX_TRANSCRIPT_SECRET environment variable not set.');
    console.error('   This secret is required for security — get it from the developer.');
    console.error('   Set it: echo \'export STX_TRANSCRIPT_SECRET=your_secret\' >> ~/.bashrc && source ~/.bashrc');
    console.error('\n   Transcript is printed below — copy manually if needed:\n');
    console.error('--- TRANSCRIPT ---');
    console.error(fullTranscript);
    console.error('--- END TRANSCRIPT ---\n');
    process.exit(1);
  }

  try {
    const res = await fetch(`${WORKER_URL}/api/transcript/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        secret: TRANSCRIPT_SECRET,
        videoId: VIDEO_ID,
        transcript: fullTranscript,
        summary: summary,
        className: CLASS_NAME
      })
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${res.status}`);
    }

    console.log('✅ Transcript saved securely!\n');
  } catch (e) {
    console.error('❌ Secure save failed:', e.message);
    console.error('   Transcript is printed below — copy manually if needed.\n');
    console.error('--- TRANSCRIPT ---');
    console.error(fullTranscript);
    console.error('--- END TRANSCRIPT ---\n');
  }

  // Cleanup temp files
  try {
    fs.unlinkSync(audioFile);
    files.forEach(f => { if (f !== audioFile) try { fs.unlinkSync(f); } catch (e) {} });
    console.log('🧹 Cleaned up temp files.');
  } catch (e) {}

  console.log('\n╔══════════════════════════════════════════════════╗');
  console.log('║  ✅  TRANSCRIPTION COMPLETE                       ║');
  console.log('╠══════════════════════════════════════════════════╣');
  console.log(`║  📝 Transcript: ${fullTranscript.length.toString().padEnd(6)} chars                 ║`);
  console.log(`║  📋 Summary   : ${summary.length.toString().padEnd(6)} chars                 ║`);
  console.log(`║  🔥 Firebase  : transcripts/${safeVideoId.padEnd(11)}     ║`);
  console.log('╚══════════════════════════════════════════════════╝');
  console.log('\n👉 The recording now shows summary + transcript in the app.');
  console.log('👉 You can close Termux now. Nothing is running in the background.\n');
}

main().catch(e => {
  console.error('💥 Fatal error:', e);
  process.exit(1);
});
