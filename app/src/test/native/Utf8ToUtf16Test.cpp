#include "utf8_to_utf16.h"

#include <algorithm>
#include <cstdlib>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace {

void expect(std::string_view utf8, std::u16string_view expected) {
    const auto decoded = localscribe::utf8_to_utf16(utf8);
    if (decoded.size() != expected.size() ||
        !std::equal(decoded.begin(), decoded.end(), expected.begin())) {
        std::abort();
    }
}

}  // namespace

int main() {
    expect("", u"");
    expect("ASCII transcript", u"ASCII transcript");
    expect(u8"Привет, мир", u"Привет, мир");
    expect(u8"日本語", u"日本語");
    expect(u8"Слово 👋 😀", u"Слово 👋 😀");
    expect("\xF0\x9F\x98\x80", u"\U0001F600");
    expect(std::string_view("a\0b", 3), std::u16string_view(u"a\0b", 3));

    // Reproduces the invalid leading 0x90 byte from the device SIGABRT.
    expect(std::string_view("\x90", 1), u"\uFFFD");
    expect(std::string_view("A\x90" "B", 3), u"A\uFFFDB");
    expect(std::string_view("\xE2\x82", 2), u"\uFFFD\uFFFD");
    expect(std::string_view("\xC0\x80", 2), u"\uFFFD\uFFFD");
    expect(std::string_view("\xED\xA0\x80", 3), u"\uFFFD\uFFFD\uFFFD");
    expect(std::string_view("\xF4\x90\x80\x80", 4),
           u"\uFFFD\uFFFD\uFFFD\uFFFD");
    expect(std::string_view("\xF0\x9F\x98\x80", 4), u"\U0001F600");

    const std::string long_text(250000, 'a');
    const auto decoded = localscribe::utf8_to_utf16(long_text);
    if (decoded.size() != long_text.size() ||
        !std::all_of(decoded.begin(), decoded.end(), [](std::uint16_t c) { return c == 'a'; })) {
        std::abort();
    }
}
