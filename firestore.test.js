const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_100";
const BOB_UID = "bob_uid_200";
const CHARLIE_UID = "charlie_uid_300";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated: cannot read or write users, directory, conversations, or calls", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 2000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("directory").doc("OC-ALICE1").get());
  await assertFails(unauthDb.collection("conversations").get());
  await assertFails(unauthDb.collection("calls").get());
  await assertFails(
    unauthDb.collection("conversations").doc("ONL-1").set({
      memberUids: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice" },
      participantColors: { [ALICE_UID]: 0 },
      title: "Hack",
      groupChat: false,
      createdBy: ALICE_UID,
      lastMessagePreview: "",
      lastSenderUid: "",
      lastMessageAtMs: 0,
      typingUids: [],
      readAtMsMap: {},
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Users & Directory: owner CRUD works, cross-user PII isolated, blanket directory list blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 5000);

  // Alice creates private profile
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      ownerUid: ALICE_UID,
      shareCode: "OC-ALICE1",
      displayName: "Alice",
      email: "alice@example.com",
      avatarColorIndex: 1,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Alice updates her profile preserving immutable fields
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Updated",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Immutable shareCode or future timestamp rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      shareCode: "OC-HACKED",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Future",
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Bob cannot read Alice's private user profile (email PII isolated)
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ displayName: "Hijack" }));

  // Alice publishes exact-code directory entry without PII
  await assertSucceeds(
    aliceDb.collection("directory").doc("OC-ALICE1").set({
      ownerUid: ALICE_UID,
      shareCode: "OC-ALICE1",
      displayName: "Alice",
      avatarColorIndex: 1,
      onlineState: "ONLINE",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Bob can look up Alice by exact shareCode, but CANNOT list/enumerate all directory entries
  await assertSucceeds(bobDb.collection("directory").doc("OC-ALICE1").get());
  await assertFails(bobDb.collection("directory").get());
  await assertFails(
    bobDb.collection("directory").doc("OC-ALICE1").update({
      displayName: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Conversations & Messages: member CRUD, read receipts, and non-member isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const past = new Date(Date.now() - 10000);
  const convId = "ONL-alice_bob";

  // Alice creates 1-on-1 conversation
  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).set({
      memberUids: [ALICE_UID, BOB_UID],
      participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      participantColors: { [ALICE_UID]: 1, [BOB_UID]: 2 },
      title: "Alice & Bob",
      groupChat: false,
      createdBy: ALICE_UID,
      lastMessagePreview: "",
      lastSenderUid: "",
      lastMessageAtMs: 0,
      typingUids: [],
      readAtMsMap: {},
      createdAt: past,
      updatedAt: past,
    })
  );

  // Filtered member query succeeds; unfiltered query fails
  await assertSucceeds(
    bobDb.collection("conversations").where("memberUids", "array-contains", BOB_UID).get()
  );
  await assertFails(bobDb.collection("conversations").get());

  // Charlie (non-member) cannot read or update conversation
  await assertFails(charlieDb.collection("conversations").doc(convId).get());

  // Alice sends a message
  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").set({
      memberUids: [ALICE_UID, BOB_UID],
      senderUid: ALICE_UID,
      senderName: "Alice",
      msgType: "TEXT",
      textBody: "Hello Bob online!",
      replyToId: "",
      replyToPreview: "",
      reactionEmoji: "",
      transferId: "",
      fileName: "",
      mimeType: "",
      fileSizeBytes: 0,
      durationMs: 0,
      deliveredUids: [ALICE_UID],
      readUids: [ALICE_UID],
      clientTimestampMs: Date.now() - 5000,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Bob reads messages & updates deliveredUids/readUids & reactionEmoji
  await assertSucceeds(
    bobDb.collection("conversations").doc(convId).collection("messages").orderBy("clientTimestampMs").get()
  );
  await assertSucceeds(
    bobDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").update({
      deliveredUids: [ALICE_UID, BOB_UID],
      readUids: [ALICE_UID, BOB_UID],
      reactionEmoji: "👍",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob cannot tamper with Alice's immutable message textBody or senderUid
  await assertFails(
    bobDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").update({
      textBody: "Tampered",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Charlie cannot read or inject messages into Alice & Bob's chat
  await assertFails(
    charlieDb.collection("conversations").doc(convId).collection("messages").get()
  );
  await assertFails(
    charlieDb.collection("conversations").doc(convId).collection("messages").doc("msg_hack").set({
      memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      senderUid: CHARLIE_UID,
      senderName: "Charlie",
      msgType: "TEXT",
      textBody: "Intruder",
      replyToId: "",
      replyToPreview: "",
      reactionEmoji: "",
      transferId: "",
      fileName: "",
      mimeType: "",
      fileSizeBytes: 0,
      durationMs: 0,
      deliveredUids: [],
      readUids: [],
      clientTimestampMs: Date.now() - 1000,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Resumable Transfers & Chunks: upload/resume/download for members only", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const past = new Date(Date.now() - 10000);
  const convId = "ONL-alice_bob";

  await aliceDb.collection("conversations").doc(convId).set({
    memberUids: [ALICE_UID, BOB_UID],
    participantNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
    participantColors: { [ALICE_UID]: 1, [BOB_UID]: 2 },
    title: "Alice & Bob",
    groupChat: false,
    createdBy: ALICE_UID,
    lastMessagePreview: "",
    lastSenderUid: "",
    lastMessageAtMs: 0,
    typingUids: [],
    readAtMsMap: {},
    createdAt: past,
    updatedAt: past,
  });

  const transferRef = aliceDb.collection("conversations").doc(convId).collection("transfers").doc("tr_1");
  await assertSucceeds(
    transferRef.set({
      memberUids: [ALICE_UID, BOB_UID],
      senderUid: ALICE_UID,
      senderName: "Alice",
      fileName: "report.pdf",
      mimeType: "application/pdf",
      totalBytes: 512000,
      chunkSizeBytes: 393216,
      totalChunks: 2,
      uploadedChunkCount: 0,
      sha256: "",
      transferState: "UPLOADING",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Upload chunk 0, pause, resume chunk 1 -> READY
  await assertSucceeds(
    transferRef.collection("chunks").doc("0").set({
      chunkIndex: 0,
      dataBase64: "SGVsbG8=",
      byteCount: 393216,
      senderUid: ALICE_UID,
      createdAt: past,
    })
  );
  await assertSucceeds(
    transferRef.update({
      uploadedChunkCount: 1,
      transferState: "PAUSED",
      updatedAt: new Date(Date.now() - 2000),
    })
  );
  await assertSucceeds(
    transferRef.collection("chunks").doc("1").set({
      chunkIndex: 1,
      dataBase64: "V29ybGQ=",
      byteCount: 118784,
      senderUid: ALICE_UID,
      createdAt: new Date(Date.now() - 1500),
    })
  );
  await assertSucceeds(
    transferRef.update({
      uploadedChunkCount: 2,
      sha256: "abc123def456",
      transferState: "READY",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Chunks are immutable once written
  await assertFails(
    transferRef.collection("chunks").doc("0").update({ dataBase64: "TAMPERED" })
  );

  // Bob can read transfer & download chunk 1 directly
  await assertSucceeds(
    bobDb.collection("conversations").doc(convId).collection("transfers").doc("tr_1").get()
  );
  await assertSucceeds(
    bobDb.collection("conversations").doc(convId).collection("transfers").doc("tr_1").collection("chunks").doc("1").get()
  );

  // Charlie cannot read transfer or chunks
  await assertFails(
    charlieDb.collection("conversations").doc(convId).collection("transfers").doc("tr_1").get()
  );
  await assertFails(
    charlieDb.collection("conversations").doc(convId).collection("transfers").doc("tr_1").collection("chunks").doc("0").get()
  );
});

test("Online Calls & ICE Candidates: member-only signaling", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const past = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").set({
      conversationId: "ONL-alice_bob",
      memberUids: [ALICE_UID, BOB_UID],
      callerUid: ALICE_UID,
      callerName: "Alice",
      calleeUid: BOB_UID,
      videoCall: true,
      callState: "RINGING",
      sdpOffer: "v=0...",
      sdpAnswer: "",
      startedAtMs: Date.now() - 5000,
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    bobDb.collection("calls").where("memberUids", "array-contains", BOB_UID).get()
  );
  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").update({
      callState: "ACTIVE",
      sdpAnswer: "v=0-answer",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").collection("candidates").doc("cand_1").set({
      senderUid: BOB_UID,
      sdpMid: "0",
      sdpMLineIndex: 0,
      candidate: "candidate:1 1 UDP 2122252543 192.168.1.2 54321 typ host",
      createdAt: new Date(Date.now() - 500),
    })
  );

  // Charlie cannot read call or candidates
  await assertFails(charlieDb.collection("calls").doc("call_1").get());
  await assertFails(charlieDb.collection("calls").doc("call_1").collection("candidates").get());
});
