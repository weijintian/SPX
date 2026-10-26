import SwiftUI
import MapKit

struct ShopLocationPicker: View {
    let initialAddress: String
    let initialCoordinate: MapCoordinate?
    let selected: (String, MapCoordinate) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var address = ""
    @State private var coordinate: MapCoordinate?
    @State private var results: [MKMapItem] = []
    @State private var searching = false
    @State private var searchID = UUID()
    @State private var error: String?

    var body: some View {
        SPXNavigationContainer {
            SPXDetailPage {
                SPXPageHeading(title: "门店位置", subtitle: "搜索地址并选择结果，也可轻点地图调整位置", icon: "location", color: SPXColor.commerce)
                SPXLegacyMap(coordinate: $coordinate).frame(height: 260).clipShape(RoundedRectangle(cornerRadius: 20))
                SPXCard {
                    VStack(alignment: .leading, spacing: 16) {
                        SPXField("详细地址") { TextField("输入城市、街道和门牌号", text: $address).lineLimit(4) }
                        if let coordinate { Divider(); InfoRow(title: "经纬度", value: String(format: "%.6f, %.6f", coordinate.latitude, coordinate.longitude)) }
                        Text("地图选点后请核对详细地址，方便用户准确找到门店。").font(.system(size: 12)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
                    }
                }
                if searching { ProgressView("正在搜索地址…").frame(maxWidth: .infinity).padding(16) }
                if !results.isEmpty {
                    SPXSectionTitle(title: "搜索结果", detail: "\(results.count) 个地点")
                    ForEach(Array(results.enumerated()), id: \.offset) { item in
                        Button { choose(item) } label: {
                            SPXCard {
                                HStack(spacing: 12) {
                                    SPXIcon("location").frame(width: 22, height: 22).foregroundStyle(SPXColor.commerce)
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(item.name ?? "地点").font(.system(size: 15, weight: .medium)).foregroundStyle(SPXColor.ink)
                                        Text(item.placemark.title ?? "").font(.system(size: 12)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                    SPXIcon("chevron").frame(width: 14, height: 14).foregroundStyle(SPXColor.muted)
                                }.frame(minHeight: 44)
                            }
                        }.buttonStyle(.plain)
                    }
                }
                if let error { ErrorBanner(message: error) }
            }
            .navigationTitle("选择门店位置")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "搜索城市与门店")
            .onSubmit(of: .search) { Task { await search() } }
            .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } } }
            .safeAreaInset(edge: .bottom) {
                SPXBottomAction {
                    Button("使用此位置") {
                        let value = address.trimmingCharacters(in: .whitespacesAndNewlines)
                        if let coordinate, !value.isEmpty { selected(value, coordinate); dismiss() }
                    }.disabled(coordinate == nil || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { address = initialAddress; query = initialAddress; coordinate = initialCoordinate }
        }
    }

    private func choose(_ item: MKMapItem) {
        let value = item.placemark.coordinate
        coordinate = MapCoordinate(latitude: value.latitude, longitude: value.longitude)
        address = item.placemark.title ?? item.name ?? address
        results = []
    }

    private func search() async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let id = UUID(); searchID = id; searching = true
        defer { if id == searchID { searching = false } }
        do {
            let request = MKLocalSearch.Request(); request.naturalLanguageQuery = text
            let response = try await MKLocalSearch(request: request).start()
            guard id == searchID, !Task.isCancelled else { return }
            results = response.mapItems; error = results.isEmpty ? "没有找到该地址，请补充城市或街道" : nil
        } catch { if id == searchID { error = error.localizedDescription } }
    }
}

private struct SPXLegacyMap: UIViewRepresentable {
    @Binding var coordinate: MapCoordinate?
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView(frame: .zero)
        map.delegate = context.coordinator; map.showsCompass = true
        map.addGestureRecognizer(UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:))))
        return map
    }
    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.owner = self
        map.removeAnnotations(map.annotations)
        if let coordinate {
            let location = CLLocationCoordinate2D(latitude: coordinate.latitude, longitude: coordinate.longitude)
            let annotation = MKPointAnnotation(); annotation.coordinate = location; map.addAnnotation(annotation)
            if !context.coordinator.didCenter { map.setRegion(MKCoordinateRegion(center: location, span: MKCoordinateSpan(latitudeDelta: 0.015, longitudeDelta: 0.015)), animated: false); context.coordinator.didCenter = true }
        }
    }
    final class Coordinator: NSObject, MKMapViewDelegate {
        var owner: SPXLegacyMap; var didCenter = false
        init(_ owner: SPXLegacyMap) { self.owner = owner }
        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let map = gesture.view as? MKMapView else { return }
            let value = map.convert(gesture.location(in: map), toCoordinateFrom: map)
            owner.coordinate = MapCoordinate(latitude: value.latitude, longitude: value.longitude)
        }
    }
}
