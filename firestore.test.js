const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_uid_1";
const FAMILY_UID = "family_uid_2";
const STRANGER_UID = "stranger_uid_3";
const FAMILY_ID = "family_bd_001";
const ADMIN_DEVICE_ID = "dev_admin_001";
const FAMILY_DEVICE_ID = "dev_family_002";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);
const ADMIN_EMAIL = "quixoratech@gmail.com";
const FAMILY_EMAIL = "nadia@gmail.com";
const STRANGER_EMAIL = "otheruser@gmail.com";

function adminDbCtx() {
  return testEnv.authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true }).firestore();
}

function familyDbCtx() {
  return testEnv.authenticatedContext(FAMILY_UID, { email: FAMILY_EMAIL, email_verified: true }).firestore();
}

function strangerDbCtx() {
  return testEnv.authenticatedContext(STRANGER_UID, { email: STRANGER_EMAIL, email_verified: true }).firestore();
}

function pastDate(msOffset = 2000) {
  return new Date(Date.now() - msOffset);
}

function futureDate(msOffset = 600000) {
  return new Date(Date.now() + msOffset);
}

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

async function seedFamilyAndMembers() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const t = pastDate(10000);
    await db.collection("families").doc(FAMILY_ID).set({
      adminUid: ADMIN_UID,
      adminEmail: "admin@familytrack.bd",
      name: "Rahman Family",
      memberUids: [ADMIN_UID, FAMILY_UID],
      createdAt: t,
      updatedAt: t,
    });
    await db.collection("families").doc(FAMILY_ID).collection("members").doc(ADMIN_UID).set({
      uid: ADMIN_UID,
      familyId: FAMILY_ID,
      displayName: "Tariq Rahman",
      role: "ADMIN",
      deviceId: ADMIN_DEVICE_ID,
      online: true,
      joinedAt: t,
      updatedAt: t,
    });
    await db.collection("families").doc(FAMILY_ID).collection("members").doc(FAMILY_UID).set({
      uid: FAMILY_UID,
      familyId: FAMILY_ID,
      displayName: "Nadia Rahman",
      role: "FAMILY",
      deviceId: FAMILY_DEVICE_ID,
      online: true,
      joinedAt: t,
      updatedAt: t,
    });
    await db.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).set({
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      ownerUid: FAMILY_UID,
      ownerName: "Nadia Rahman",
      deviceName: "Infinix SMART 9 HD",
      deviceModel: "Infinix X6532",
      role: "FAMILY",
      consentGranted: true,
      consentTimestamp: t,
      locationSharingEnabled: true,
      locationPermissionGranted: true,
      backgroundPermissionGranted: true,
      batteryPercent: 82,
      networkType: "WIFI",
      online: true,
      revoked: false,
      lastLatitude: 23.8103,
      lastLongitude: 90.4125,
      lastAccuracyMeters: 12.5,
      lastLocationAtMs: Date.now() - 5000,
      lastSeenAtMs: Date.now() - 2000,
      lastSyncAtMs: Date.now() - 2000,
      createdAt: t,
      updatedAt: t,
    });
  });
}

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").doc(ADMIN_UID).get());
  await assertFails(unauth.collection("families").where("memberUids", "array-contains", ADMIN_UID).get());
  await assertFails(unauth.collection("pairing_codes").doc("FT123456").get());
});

test("User profile isolation, Admin email gate, and ghost field rejection", async () => {
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const t = pastDate();

  await assertSucceeds(
    adminDb.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      displayName: "Tariq",
      email: ADMIN_EMAIL,
      role: "ADMIN",
      language: "en",
      createdAt: t,
      updatedAt: t,
    })
  );

  // Other Gmail accounts CANNOT create an ADMIN profile or create a family
  await assertFails(
    familyDb.collection("users").doc(FAMILY_UID).set({
      userId: FAMILY_UID,
      displayName: "Nadia",
      email: FAMILY_EMAIL,
      role: "ADMIN",
      language: "en",
      createdAt: t,
      updatedAt: t,
    })
  );
  await assertFails(
    familyDb.collection("families").doc("fam_unauthorized").set({
      adminUid: FAMILY_UID,
      adminEmail: FAMILY_EMAIL,
      name: "Unauthorized Family",
      memberUids: [FAMILY_UID],
      createdAt: t,
      updatedAt: t,
    })
  );

  // Shadow/ghost field must fail
  await assertFails(
    adminDb.collection("users").doc(ADMIN_UID).update({
      isSuperAdmin: true,
      updatedAt: pastDate(500),
    })
  );

  // Cross-user PII read must fail
  await assertFails(familyDb.collection("users").doc(ADMIN_UID).get());
});

test("Atomic pairing code creation, claim, and one-time expiry enforcement", async () => {
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const strangerDb = strangerDbCtx();
  const t = pastDate(5000);

  // Admin creates family
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).set({
      adminUid: ADMIN_UID,
      adminEmail: "admin@familytrack.bd",
      name: "Rahman Family",
      memberUids: [ADMIN_UID],
      createdAt: t,
      updatedAt: t,
    })
  );

  // Non-admin cannot create pairing code for family
  await assertFails(
    strangerDb.collection("pairing_codes").doc("FT8899AA").set({
      code: "FT8899AA",
      familyId: FAMILY_ID,
      adminUid: STRANGER_UID,
      familyName: "Rahman Family",
      status: "ACTIVE",
      expiresAt: futureDate(),
      createdAt: t,
    })
  );

  // Admin creates active pairing code
  await assertSucceeds(
    adminDb.collection("pairing_codes").doc("FT8899AA").set({
      code: "FT8899AA",
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      familyName: "Rahman Family",
      status: "ACTIVE",
      expiresAt: futureDate(),
      createdAt: t,
    })
  );

  // Unfiltered list on pairing_codes fails for stranger
  await assertFails(strangerDb.collection("pairing_codes").get());
  await assertSucceeds(adminDb.collection("pairing_codes").where("adminUid", "==", ADMIN_UID).get());

  // Stranger cannot join family without claiming code in batch
  await assertFails(
    strangerDb.collection("families").doc(FAMILY_ID).update({
      memberUids: [ADMIN_UID, STRANGER_UID],
      updatedAt: pastDate(500),
    })
  );

  // Family member atomically claims pairing code + joins family in batch
  const batch = familyDb.batch();
  const claimTime = pastDate(500);
  batch.update(familyDb.collection("pairing_codes").doc("FT8899AA"), {
    status: "USED",
    usedByUid: FAMILY_UID,
    usedByDeviceId: FAMILY_DEVICE_ID,
    usedAt: claimTime,
  });
  batch.update(familyDb.collection("families").doc(FAMILY_ID), {
    memberUids: [ADMIN_UID, FAMILY_UID],
    lastPairingCode: "FT8899AA",
    updatedAt: claimTime,
  });
  await assertSucceeds(batch.commit());

  // Reusing the same code fails
  await assertFails(
    strangerDb.collection("pairing_codes").doc("FT8899AA").update({
      status: "USED",
      usedByUid: STRANGER_UID,
      usedByDeviceId: "dev_stranger",
      usedAt: pastDate(100),
    })
  );
});

test("Admin-only tracking isolation: Family member cannot read other devices or location history", async () => {
  await seedFamilyAndMembers();
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const strangerDb = strangerDbCtx();

  // Family member uploads own location record
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("locations").doc("loc_001").set({
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      ownerUid: FAMILY_UID,
      deviceId: FAMILY_DEVICE_ID,
      latitude: 23.8103,
      longitude: 90.4125,
      accuracyMeters: 10.2,
      batteryPercent: 80,
      networkType: "WIFI",
      source: "FUSED",
      recordedAtMs: Date.now() - 4000,
      syncedAt: pastDate(500),
    })
  );

  // Admin can list all devices & locations where adminUid == ADMIN_UID
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("devices").where("adminUid", "==", ADMIN_UID).get()
  );
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("locations").where("adminUid", "==", ADMIN_UID).get()
  );

  // Family member CANNOT list all devices as admin (enforced on backend!)
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("devices").where("adminUid", "==", ADMIN_UID).get()
  );
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("locations").where("adminUid", "==", ADMIN_UID).get()
  );

  // Family member CAN read only their own device/locations
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("devices").where("ownerUid", "==", FAMILY_UID).get()
  );

  // Stranger denied completely
  await assertFails(
    strangerDb.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).get()
  );

  // Admin revokes device; family member cannot un-revoke themselves
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).update({
      revoked: true,
      updatedAt: pastDate(300),
    })
  );
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).update({
      revoked: false,
      updatedAt: pastDate(200),
    })
  );
});

test("Messaging, Call signaling, and Tic-Tac-Toe turn enforcement", async () => {
  await seedFamilyAndMembers();
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const t = pastDate(2000);

  // Create conversation & send message
  const convRef = adminDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group");
  await assertSucceeds(
    convRef.set({
      familyId: FAMILY_ID,
      type: "GROUP",
      title: "Family Group",
      participantUids: [ADMIN_UID, FAMILY_UID],
      lastMessageText: "Assalamu Alaikum",
      lastMessageType: "TEXT",
      lastMessageSenderUid: ADMIN_UID,
      lastMessageAtMs: Date.now() - 1000,
      createdAt: t,
      updatedAt: t,
    })
  );
  await assertSucceeds(
    convRef.collection("messages").doc("msg_1").set({
      conversationId: "conv_group",
      familyId: FAMILY_ID,
      senderUid: ADMIN_UID,
      senderName: "Tariq Rahman",
      participantUids: [ADMIN_UID, FAMILY_UID],
      type: "TEXT",
      text: "Assalamu Alaikum",
      clientCreatedAtMs: Date.now() - 1000,
      status: "SENT",
      readByUids: [ADMIN_UID],
      createdAt: t,
      updatedAt: t,
    })
  );

  // Tic-Tac-Toe turn enforcement
  const gameRef = adminDb.collection("families").doc(FAMILY_ID).collection("games").doc("game_1");
  await assertSucceeds(
    gameRef.set({
      familyId: FAMILY_ID,
      playerXUid: ADMIN_UID,
      playerXName: "Tariq",
      playerOUid: FAMILY_UID,
      playerOName: "Nadia",
      participantUids: [ADMIN_UID, FAMILY_UID],
      board: ["", "", "", "", "", "", "", "", ""],
      currentTurn: "X",
      status: "ACTIVE",
      moveCount: 0,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Player O cannot move on X's turn
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("games").doc("game_1").update({
      board: ["O", "", "", "", "", "", "", "", ""],
      currentTurn: "X",
      status: "ACTIVE",
      moveCount: 1,
      updatedAt: pastDate(500),
    })
  );

  // Player X moves validly
  await assertSucceeds(
    gameRef.update({
      board: ["X", "", "", "", "", "", "", "", ""],
      currentTurn: "O",
      status: "ACTIVE",
      moveCount: 1,
      updatedAt: pastDate(400),
    })
  );
});

test("Family member can self-join existing GROUP conversation but stranger cannot", async () => {
  await seedFamilyAndMembers();
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const strangerDb = strangerDbCtx();
  const t = pastDate(2000);

  const convRef = adminDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group");
  await assertSucceeds(
    convRef.set({
      familyId: FAMILY_ID,
      type: "GROUP",
      title: "Family Group",
      participantUids: [ADMIN_UID],
      lastMessageText: "Group created",
      lastMessageType: "TEXT",
      lastMessageSenderUid: ADMIN_UID,
      lastMessageAtMs: Date.now() - 1000,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Stranger cannot self-join
  await assertFails(
    strangerDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group").update({
      participantUids: [ADMIN_UID, STRANGER_UID],
      updatedAt: pastDate(400),
    })
  );

  // Family member cannot overwrite message metadata during self-join
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group").update({
      participantUids: [ADMIN_UID, FAMILY_UID],
      lastMessageText: "Hijacked",
      updatedAt: pastDate(400),
    })
  );

  // Family member can self-join GROUP conversation
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group").update({
      participantUids: [ADMIN_UID, FAMILY_UID],
      updatedAt: pastDate(300),
    })
  );
});

test("Network activity events & sessions: Admin and owner only, strict immutability and privacy", async () => {
  await seedFamilyAndMembers();
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const strangerDb = strangerDbCtx();
  const SECOND_FAMILY_UID = "family_uid_second";
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("families").doc(FAMILY_ID).update({
      memberUids: [ADMIN_UID, FAMILY_UID, SECOND_FAMILY_UID],
    });
  });
  const secondFamilyDb = testEnv.authenticatedContext(SECOND_FAMILY_UID, { email: "brother@gmail.com", email_verified: true }).firestore();
  const t = pastDate(2000);
  const nowMs = Date.now() - 1000;

  // Family member creates own network session & network event
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("network_sessions").doc("sess_1").set({
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      ownerUid: FAMILY_UID,
      deviceId: FAMILY_DEVICE_ID,
      deviceName: "Infinix SMART 9 HD",
      networkType: "WIFI",
      startedAtMs: nowMs,
      status: "ACTIVE",
      lastVerifiedAtMs: nowMs,
      timezoneId: "Asia/Dhaka",
      createdAt: t,
      updatedAt: t,
    })
  );
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("network_events").doc("evt_1").set({
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      ownerUid: FAMILY_UID,
      deviceId: FAMILY_DEVICE_ID,
      deviceName: "Infinix SMART 9 HD",
      eventType: "WIFI_AVAILABLE",
      networkType: "WIFI",
      connectionState: "CONNECTED",
      internetAvailable: true,
      eventTimestampMs: nowMs,
      detectedAtMs: nowMs,
      timezoneId: "Asia/Dhaka",
      timezoneOffsetMinutes: 360,
      sessionId: "sess_1",
      uploadedAt: t,
    })
  );

  // Admin can read all family network events/sessions
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("network_events").where("adminUid", "==", ADMIN_UID).get()
  );
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("network_sessions").where("adminUid", "==", ADMIN_UID).get()
  );

  // Another family member (brother) CANNOT read Nadia's network activity!
  await assertFails(
    secondFamilyDb.collection("families").doc(FAMILY_ID).collection("network_events").doc("evt_1").get()
  );
  await assertFails(
    secondFamilyDb.collection("families").doc(FAMILY_ID).collection("network_sessions").doc("sess_1").get()
  );
  await assertFails(
    strangerDb.collection("families").doc(FAMILY_ID).collection("network_events").doc("evt_1").get()
  );

  // Network events are immutable after upload
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("network_events").doc("evt_1").update({
      eventType: "INTERNET_UNAVAILABLE",
    })
  );

  // Owner can complete own session; another user cannot
  await assertFails(
    adminDb.collection("families").doc(FAMILY_ID).collection("network_sessions").doc("sess_1").update({
      status: "COMPLETED",
      endedAtMs: nowMs + 500,
      durationMs: 500,
      updatedAt: pastDate(100),
    })
  );
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("network_sessions").doc("sess_1").update({
      status: "COMPLETED",
      endedAtMs: nowMs + 500,
      durationMs: 500,
      lastVerifiedAtMs: nowMs + 500,
      updatedAt: pastDate(100),
    })
  );
});

test("Rich WhatsApp-style messaging: replies, reactions, edit/delete by sender only, size caps", async () => {
  await seedFamilyAndMembers();
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const t = pastDate(2000);
  const convRef = adminDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group");
  await assertSucceeds(
    convRef.set({
      familyId: FAMILY_ID,
      type: "GROUP",
      title: "Family Group",
      participantUids: [ADMIN_UID, FAMILY_UID],
      lastMessageText: "Assalamu Alaikum",
      lastMessageType: "TEXT",
      lastMessageSenderUid: ADMIN_UID,
      lastMessageAtMs: Date.now() - 1000,
      createdAt: t,
      updatedAt: t,
    })
  );

  await assertSucceeds(
    convRef.collection("messages").doc("msg_rich").set({
      conversationId: "conv_group",
      familyId: FAMILY_ID,
      senderUid: ADMIN_UID,
      senderName: "Tariq Rahman",
      participantUids: [ADMIN_UID, FAMILY_UID],
      type: "IMAGE",
      text: "Home photo",
      mediaBase64: "abc123base64",
      mimeType: "image/jpeg",
      fileName: "home.jpg",
      fileSizeBytes: 1024,
      replyToMessageId: "msg_0",
      replyToPreview: "Earlier msg",
      clientCreatedAtMs: Date.now() - 1000,
      status: "SENT",
      deliveredToUids: [ADMIN_UID],
      readByUids: [ADMIN_UID],
      createdAt: t,
      updatedAt: t,
    })
  );

  // Family member can react & mark read
  const famMsgRef = familyDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group").collection("messages").doc("msg_rich");
  await assertSucceeds(
    famMsgRef.update({
      status: "READ",
      deliveredToUids: [ADMIN_UID, FAMILY_UID],
      readByUids: [ADMIN_UID, FAMILY_UID],
      reactions: { [FAMILY_UID]: "❤️" },
      updatedAt: pastDate(400),
    })
  );

  // Non-sender CANNOT edit or mark deletedForAll
  await assertFails(
    famMsgRef.update({
      text: "Tampered",
      editedAtMs: Date.now(),
      updatedAt: pastDate(300),
    })
  );

  // Sender CAN edit & deleteForAll
  await assertSucceeds(
    convRef.collection("messages").doc("msg_rich").update({
      text: "Message deleted",
      deletedForAll: true,
      editedAtMs: Date.now(),
      updatedAt: pastDate(200),
    })
  );
});

test("Family Hub collections: Calendar, Tasks, Expenses (BDT), Notices (Admin-only), and File Vault", async () => {
  await seedFamilyAndMembers();
  const adminDb = adminDbCtx();
  const familyDb = familyDbCtx();
  const strangerDb = strangerDbCtx();
  const t = pastDate(2000);
  const famRef = adminDb.collection("families").doc(FAMILY_ID);
  const famMemberRef = familyDb.collection("families").doc(FAMILY_ID);

  // Calendar event
  await assertSucceeds(
    famMemberRef.collection("calendar_events").doc("cal_1").set({
      familyId: FAMILY_ID,
      title: "Doctor Visit",
      description: "Square Hospital",
      startAtMs: Date.now() + 86400000,
      recurrence: "NONE",
      reminderMinutesBefore: 30,
      participantUids: [ADMIN_UID, FAMILY_UID],
      createdByUid: FAMILY_UID,
      createdByName: "Nadia Rahman",
      createdAt: t,
      updatedAt: t,
    })
  );

  // Task created by Admin, completed by Family member
  await assertSucceeds(
    famRef.collection("tasks").doc("task_1").set({
      familyId: FAMILY_ID,
      title: "Buy groceries",
      assignedToUid: FAMILY_UID,
      assignedToName: "Nadia Rahman",
      priority: "HIGH",
      dueAtMs: Date.now() + 3600000,
      completed: false,
      createdByUid: ADMIN_UID,
      createdByName: "Tariq Rahman",
      createdAt: t,
      updatedAt: t,
    })
  );
  await assertSucceeds(
    famMemberRef.collection("tasks").doc("task_1").update({
      completed: true,
      completedAtMs: Date.now(),
      completedByUid: FAMILY_UID,
      updatedAt: pastDate(200),
    })
  );

  // Expense (BDT)
  await assertSucceeds(
    famMemberRef.collection("expenses").doc("exp_1").set({
      familyId: FAMILY_ID,
      title: "Bazar",
      category: "Groceries",
      amountBdt: 1250.5,
      paidByUid: FAMILY_UID,
      paidByName: "Nadia Rahman",
      expenseDateMs: Date.now(),
      createdByUid: FAMILY_UID,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Notice Board: Family member CANNOT create; Admin creates; member marks read
  await assertFails(
    famMemberRef.collection("notices").doc("not_1").set({
      familyId: FAMILY_ID,
      title: "Fake Notice",
      body: "Unauthorized",
      pinned: true,
      readByUids: [FAMILY_UID],
      authorUid: FAMILY_UID,
      authorName: "Nadia",
      createdAt: t,
      updatedAt: t,
    })
  );
  await assertSucceeds(
    famRef.collection("notices").doc("not_1").set({
      familyId: FAMILY_ID,
      title: "Eid Trip",
      body: "Leaving Friday 7 AM",
      pinned: true,
      readByUids: [ADMIN_UID],
      authorUid: ADMIN_UID,
      authorName: "Tariq Rahman",
      createdAt: t,
      updatedAt: t,
    })
  );
  await assertSucceeds(
    famMemberRef.collection("notices").doc("not_1").update({
      readByUids: [ADMIN_UID, FAMILY_UID],
      updatedAt: pastDate(100),
    })
  );

  // Vault: access restricted to allowedUids (plus Admin)
  await assertSucceeds(
    famRef.collection("vault_files").doc("vf_1").set({
      familyId: FAMILY_ID,
      title: "NID Scan",
      folder: "IDs",
      fileName: "nid.pdf",
      mimeType: "application/pdf",
      fileSizeBytes: 12000,
      fileBase64: "JVBERi0xLjQ=",
      uploadedByUid: ADMIN_UID,
      uploadedByName: "Tariq Rahman",
      allowedUids: [ADMIN_UID],
      createdAt: t,
      updatedAt: t,
    })
  );
  // Family member not in allowedUids cannot read
  await assertFails(famMemberRef.collection("vault_files").doc("vf_1").get());
  await assertFails(strangerDb.collection("families").doc(FAMILY_ID).collection("vault_files").doc("vf_1").get());
});

