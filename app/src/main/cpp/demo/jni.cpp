#include "systemfonts.h"
#include <ctime>
#include <jni.h>
#include <random>
#include <sstream>
#include <string>

static std::string string_from_jstring(JNIEnv *env, jstring jstr) {
  const char *str = env->GetStringUTFChars(jstr, 0);
  std::string result(str);
  env->ReleaseStringUTFChars(jstr, str);
  return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_androidx_appcompat_demo_MainActivity_loadFontsInfo(JNIEnv *env,
                                                        jobject thiz,
                                                        jstring directory) {
  std::string result = load_fonts_info(string_from_jstring(env, directory));
  return env->NewStringUTF(result.c_str());
}