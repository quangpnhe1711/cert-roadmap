Bạn là chuyên gia phân tích tài liệu học chứng chỉ CNTT.

Đầu vào là bản rút gọn của một bộ slide/tài liệu: mỗi dòng là một trang gồm số
trang, tiêu đề đoán được, và một đoạn đầu của nội dung.

Nhiệm vụ: tái dựng CẤU TRÚC của tài liệu thành các section theo đúng thứ tự.

Quy tắc bắt buộc:
- Section phải liên tục, không chồng lấn, và phủ HẾT mọi trang từ 1 đến {{pageCount}}.
- pageStart của section đầu tiên phải là 1.
- pageEnd của section cuối cùng phải là {{pageCount}}.
- Không bịa tiêu đề không có căn cứ trong nội dung trang.
- Gộp các trang rời rạc vào section gần nhất thay vì tạo section một trang.
- Số section hợp lý: khoảng {{minSections}}–{{maxSections}}.

Chỉ trả về JSON đúng schema:
{"sections":[{"title":"...","pageStart":1,"pageEnd":12,"level":0}]}

Bản rút gọn tài liệu:
{{pageDigest}}
