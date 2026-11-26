# ফোন থেকে FamilyTrack BD GitHub-এ আপলোড ও APK Build

এই নির্দেশিকা `quixoratech/Family-trac` রিপোজিটরির জন্য তৈরি। ZIP-এর ভেতরে প্রজেক্টের ফাইলগুলো সরাসরি root-এ আছে; আলাদা parent folder নেই।

## ১. ZIP Extract করো

1. `FamilyTrack-BD-GitHub-Upload-Ready.zip` ফোনে ডাউনলোড করে ZArchiver বা Files দিয়ে Extract করো।
2. Extract করা ফোল্ডার খুললে `app`, `gradle`, `.github`, `build.gradle.kts`, `settings.gradle.kts`, `firestore.rules` ইত্যাদি সরাসরি দেখতে পাবে।
3. ZIP ফাইলটিকে নিজে GitHub-এ upload করবে না। Extract করা project files upload করতে হবে।

## ২. Repository-তে সাধারণ ফাইল upload করো

1. এই ঠিকানায় যাও: https://github.com/quixoratech/Family-trac
2. `Add file` → `Upload files` চাপো।
3. Extract করা project-এর সব সাধারণ ফাইল ও folder upload করো। GitHub web uploader-এ একবারে সর্বোচ্চ 100টি file upload করা যায়; এই প্যাকেজে workflow file বাদে 99টি project file আছে।
4. `.github` hidden folder যদি file picker-এ না দেখা যায়, সেটি নিয়ে চিন্তা কোরো না—পরের ধাপে workflow আলাদাভাবে তৈরি করবে।
5. Commit message দাও `Add FamilyTrack BD project` এবং `Commit changes` চাপো। Branch `main` রাখো।

## ৩. GitHub Actions workflow যোগ করো (অবশ্যই)

1. Repository-তে `Add file` → `Create new file` চাপো।
2. Filename ঘরে হুবহু লিখো: `.github/workflows/android-apk.yml`
3. Extract করা ZIP-এর `.github/workflows/android-apk.yml` ফাইল খুলে সম্পূর্ণ content copy করে নতুন GitHub file-এ paste করো।
4. `Commit changes` চাপো।

Workflow file যোগ হলে GitHub Actions স্বয়ংক্রিয়ভাবে `main` branch-এর push-এ APK build করবে। চাইলে `Actions` → `Build FamilyTrack BD APK` → `Run workflow` দিয়েও manual build চালাতে পারো।

## ৪. APK সংগ্রহ করো

1. Repository-র `Actions` tab খোলো।
2. সর্বশেষ `Build FamilyTrack BD APK` run খুলে build সফল হওয়া পর্যন্ত অপেক্ষা করো।
3. সফল run-এর নিচের `Artifacts` থেকে `FamilyTrackBD-debug-apk` ডাউনলোড করো।
4. Artifact ZIP extract করলে `app-debug.apk` পাবে।

## গুরুত্বপূর্ণ সীমাবদ্ধতা ও নিরাপত্তা

- এই workflow **debug APK** তৈরি করে; এটি Play Store release build নয়।
- Build সফল হওয়া মানেই সব feature বাস্তব ফোনে পরীক্ষা হয়েছে—এমন নয়। Firebase rules deploy ও test করতে হবে।
- `app/google-services.json` Firebase client configuration। Repository-টি Private রাখো এবং Firestore security rules-কে অবশ্যই নিরাপদভাবে deploy/test করো। কোনো service-account JSON, private key, keystore বা password repository-তে upload কোরো না।
- Location ও network history কেবল সংশ্লিষ্ট ব্যবহারকারীর স্পষ্ট সম্মতিতে চালু করতে হবে। Android background execution সীমাবদ্ধতার কারণে প্রতিটি event-এর নির্ভুল সময় বা অবিচ্ছিন্ন tracking নিশ্চিত করা যায় না।
- Build ব্যর্থ হলে Actions run-এর `Build debug APK` step-এর শেষের error message কপি করে পাঠাও।
